#!/usr/bin/env python3
"""Prepare, control, and summarize local coupon-detail cache experiments."""

import argparse
import csv
import http.client
import json
import math
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parent.parent
OUTPUT_ROOT = ROOT / "target" / "perf-cache"
RUN_ID = re.compile(r"[A-Za-z0-9_-]{1,40}\Z")
LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}


def parse_base(raw_base: str):
    parsed = urlparse(raw_base)
    # COSEC: this perf utility is intentionally restricted to a local HTTP target.
    if (parsed.scheme != "http" or parsed.hostname not in LOCAL_HOSTS or parsed.username
            or parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("--base 仅允许 http://127.0.0.1:<port>、http://localhost:<port> 或 http://[::1]:<port>")
    if parsed.port is None or not 1 <= parsed.port <= 65535:
        raise ValueError("--base 必须显式提供有效端口")
    return parsed.hostname, parsed.port


def require_run_id(run_id: str) -> str:
    if not RUN_ID.fullmatch(run_id):
        raise ValueError("run-id 仅允许字母、数字、下划线和连字符，长度 1-40")
    return run_id


class LocalApi:
    def __init__(self, base: str):
        self.host, self.port = parse_base(base)

    def request(self, method: str, path: str, body=None, token=None):
        payload = None if body is None else json.dumps(body, ensure_ascii=True).encode("utf-8")
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        connection = http.client.HTTPConnection(self.host, self.port, timeout=20)
        try:
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            raw = response.read()
        finally:
            connection.close()
        try:
            decoded = json.loads(raw.decode("utf-8")) if raw else {}
        except json.JSONDecodeError as error:
            raise RuntimeError(f"{method} {path} returned invalid JSON (HTTP {response.status})") from error
        if response.status >= 400 or decoded.get("code") != 0:
            raise RuntimeError(f"{method} {path} failed (HTTP {response.status}): {decoded}")
        return decoded.get("data")


def merchant_token(api: LocalApi, merchant: str, password: str) -> str:
    data = api.request("POST", "/api/auth/login", {
        "username": merchant,
        "password": password,
        "role": "MERCHANT",
    })
    token = data.get("accessToken") if isinstance(data, dict) else None
    if not token:
        raise RuntimeError("merchant login response did not contain accessToken")
    return token


def run_directory(run_id: str) -> Path:
    return OUTPUT_ROOT / require_run_id(run_id)


def load_manifest(run_id: str) -> dict:
    path = run_directory(run_id) / "manifest.json"
    if not path.is_file():
        raise RuntimeError(f"缺少测试清单: {path}; 请先执行 prepare")
    return json.loads(path.read_text(encoding="utf-8"))


def write_dataset(manifest: dict, token: str, dataset_rows: int):
    """Keep coupon selection stable while replacing a potentially expired JWT."""
    dataset = Path(manifest["dataset"])
    coupon_ids = manifest["coupon_ids"]
    hot_ids = manifest["hot_coupon_ids"]
    with dataset.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        for index in range(dataset_rows):
            # Four of every five rows target one of ten designated hot coupons.
            coupon_id = hot_ids[index % len(hot_ids)] if index % 5 else coupon_ids[10 + (index // 5) % 90]
            writer.writerow((coupon_id, token))


def prepare(args):
    run_id = require_run_id(args.run_id)
    api = LocalApi(args.base)
    token = merchant_token(api, args.merchant, args.password)
    api.request("DELETE", f"/api/perf/cache/fixtures/{run_id}", token=token)
    fixture = api.request("POST", "/api/perf/cache/fixtures", {"runId": run_id, "count": 100}, token)
    coupon_ids = fixture.get("couponIds", [])
    hot_ids = fixture.get("hotCouponIds", [])
    if len(coupon_ids) != 100 or len(hot_ids) != 10:
        raise RuntimeError(f"fixture creation returned unexpected ids: {fixture}")

    destination = run_directory(run_id)
    destination.mkdir(parents=True, exist_ok=True)
    manifest = {
        "run_id": run_id,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "base": args.base,
        "coupon_ids": coupon_ids,
        "hot_coupon_ids": hot_ids,
        "dataset": str(destination / "requests.csv"),
        "distribution": "80% across 10 hot coupons; 20% across 90 normal coupons",
    }
    write_dataset(manifest, token, args.dataset_rows)
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in manifest.items() if key != "token"}, indent=2))


def set_up_case(args):
    manifest = load_manifest(args.run_id)
    api = LocalApi(args.base)
    token = merchant_token(api, args.merchant, args.password)
    # Refresh only credentials before every JMeter sample. Coupon IDs and request mix stay fixed.
    write_dataset(manifest, token, args.dataset_rows)
    coupon_ids = manifest["coupon_ids"]
    if args.case == "mysql":
        body = {"couponIds": coupon_ids, "clearL1": True, "clearRedis": False, "resetMetrics": True}
        api.request("POST", "/api/perf/cache/reset", body, token)
    else:
        mode = "REDIS_SNAPSHOT" if args.case == "redis" else "CAFFEINE_SNAPSHOT"
        api.request("POST", "/api/perf/cache/prewarm", {"couponIds": coupon_ids, "mode": mode}, token)
        body = {"couponIds": coupon_ids, "clearL1": args.case == "redis",
                "clearRedis": False, "resetMetrics": True}
        api.request("POST", "/api/perf/cache/reset", body, token)
    print(json.dumps({"run_id": args.run_id, "case": args.case, "status": "ready"}))


def collect(args):
    manifest = load_manifest(args.run_id)
    api = LocalApi(args.base)
    token = merchant_token(api, args.merchant, args.password)
    metrics = api.request("GET", "/api/perf/cache/metrics", token=token)
    jtl_path = Path(args.jtl).resolve()
    if not jtl_path.is_file():
        raise RuntimeError(f"JTL 不存在: {jtl_path}")
    rows = list(csv.DictReader(jtl_path.open(encoding="utf-8-sig", newline="")))
    if not rows:
        raise RuntimeError("JTL 没有样本")
    elapsed = [float(row["elapsed"]) for row in rows]
    starts = [float(row["timeStamp"]) for row in rows]
    ends = [start + duration for start, duration in zip(starts, elapsed)]
    duration_seconds = (max(ends) - min(starts)) / 1000
    successful = sum(row.get("success", "").lower() == "true" for row in rows)
    summary = {
        "run_id": args.run_id,
        "case": args.case,
        "threads": args.threads,
        "jtl": str(jtl_path),
        "requests": len(rows),
        "duration_seconds": round(duration_seconds, 3),
        "qps": round(len(rows) / duration_seconds, 2) if duration_seconds else 0.0,
        "average_ms": round(sum(elapsed) / len(elapsed), 2),
        "p50_ms": percentile(elapsed, 0.50),
        "p95_ms": percentile(elapsed, 0.95),
        "p99_ms": percentile(elapsed, 0.99),
        "max_ms": round(max(elapsed), 2),
        "http_or_api_error_count": len(rows) - successful,
        "http_or_api_error_rate": round((len(rows) - successful) / len(rows), 6),
        "cache_metrics": metrics,
        "dataset_distribution": manifest["distribution"],
    }
    output = run_directory(args.run_id) / f"{args.case}-{args.threads}-summary.json"
    output.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


def report(args):
    run_id = require_run_id(args.run_id)
    summaries = []
    for case in ("mysql", "redis", "caffeine"):
        for threads in args.threads:
            path = run_directory(run_id) / f"{case}-{threads}-summary.json"
            if not path.is_file():
                raise RuntimeError(f"缺少正式结果: {path}")
            summaries.append(json.loads(path.read_text(encoding="utf-8")))

    lines = [
        "| 方案 | 并发 | QPS | P50 (ms) | P95 (ms) | P99 (ms) | Average (ms) | Max (ms) | 错误率 | MySQL 查询 | Redis 缓存读 | Caffeine 命中率 |",
        "| -- | -: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |",
    ]
    for item in summaries:
        metrics = item["cache_metrics"]
        redis_reads = metrics["redis_version_pointer_read"] + metrics["redis_snapshot_hit"] + metrics["redis_snapshot_miss"]
        labels = {"mysql": "MySQL 直查", "redis": "Redis 快照", "caffeine": "Caffeine + Redis"}
        lines.append(
            f"| {labels[item['case']]} | {item['threads']} | {item['qps']:.2f} | "
            f"{item['p50_ms']:.2f} | {item['p95_ms']:.2f} | {item['p99_ms']:.2f} | "
            f"{item['average_ms']:.2f} | {item['max_ms']:.2f} | "
            f"{item['http_or_api_error_rate'] * 100:.4f}% | {metrics['db_load']} | {redis_reads} | "
            f"{metrics['caffeine_hit_rate'] * 100:.2f}% |"
        )
    report_path = run_directory(run_id) / "report.md"
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def percentile(values, ratio):
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * ratio) - 1)], 2)


def cleanup(args):
    run_id = require_run_id(args.run_id)
    api = LocalApi(args.base)
    token = merchant_token(api, args.merchant, args.password)
    print(json.dumps(api.request("DELETE", f"/api/perf/cache/fixtures/{run_id}", token=token), indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--merchant", default="merchant_food")
    parser.add_argument("--password", default=os.environ.get("PERF_MERCHANT_PASSWORD"))
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--run-id", required=True)
    prepare_parser.add_argument("--dataset-rows", type=int, default=10000)
    prepare_parser.set_defaults(func=prepare)

    setup_parser = subparsers.add_parser("setup-case")
    setup_parser.add_argument("--run-id", required=True)
    setup_parser.add_argument("--case", choices=("mysql", "redis", "caffeine"), required=True)
    setup_parser.add_argument("--dataset-rows", type=int, default=10000)
    setup_parser.set_defaults(func=set_up_case)

    collect_parser = subparsers.add_parser("collect")
    collect_parser.add_argument("--run-id", required=True)
    collect_parser.add_argument("--case", choices=("mysql", "redis", "caffeine"), required=True)
    collect_parser.add_argument("--jtl", required=True)
    collect_parser.add_argument("--threads", type=int, required=True)
    collect_parser.set_defaults(func=collect)

    report_parser = subparsers.add_parser("report")
    report_parser.add_argument("--run-id", required=True)
    report_parser.add_argument("--threads", type=int, nargs="+", default=[10, 50, 100, 200])
    report_parser.set_defaults(func=report)

    cleanup_parser = subparsers.add_parser("cleanup")
    cleanup_parser.add_argument("--run-id", required=True)
    cleanup_parser.set_defaults(func=cleanup)

    args = parser.parse_args()
    if not args.password:
        parser.error("请通过 PERF_MERCHANT_PASSWORD 环境变量或 --password 提供商家密码")
    try:
        args.func(args)
    except (OSError, ValueError, RuntimeError, http.client.HTTPException) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
