#!/usr/bin/env python3
"""Create, verify, clean, and summarize local seckill performance fixtures."""

import argparse
import base64
import csv
import http.client
import json
import math
import os
import re
import sys
import time
from collections import Counter
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
DATA_ROOT = ROOT / "data"
RESULT_ROOT = ROOT / "results"
RUN_ID = re.compile(r"[A-Za-z0-9_-]{1,40}\Z")
LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}


def local_http_timeout():
    raw = os.environ.get("PERF_HTTP_TIMEOUT_SECONDS", "20")
    try:
        value = int(raw)
    except ValueError as error:
        raise ValueError("PERF_HTTP_TIMEOUT_SECONDS 必须是整数") from error
    if not 1 <= value <= 900:
        raise ValueError("PERF_HTTP_TIMEOUT_SECONDS 必须在 1-900 秒")
    return value


def require_run_id(value):
    if not RUN_ID.fullmatch(value):
        raise ValueError("run-id 仅允许字母、数字、下划线和连字符，长度 1-40")
    return value


def parse_local_base(value):
    parsed = urlparse(value)
    # COSEC: fixture credentials may only be sent to an explicitly local, plain HTTP target.
    if (parsed.scheme != "http" or parsed.hostname not in LOCAL_HOSTS or parsed.username
            or parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("--base 仅允许 http://127.0.0.1:<port>、http://localhost:<port> 或 http://[::1]:<port>")
    if parsed.port is None or not 1 <= parsed.port <= 65535:
        raise ValueError("--base 必须包含有效端口")
    return parsed.hostname, parsed.port


def run_dir(root, run_id):
    candidate = (root / require_run_id(run_id)).resolve()
    root_resolved = root.resolve()
    try:
        candidate.relative_to(root_resolved)
    except ValueError as error:
        raise ValueError("run-id 路径非法") from error
    return candidate


def require_file_under(path_value, root):
    path = Path(path_value).resolve()
    try:
        path.relative_to(root.resolve())
    except ValueError as error:
        raise ValueError(f"文件必须位于 {root}") from error
    if not path.is_file():
        raise FileNotFoundError(path)
    return path


class LocalApi:
    def __init__(self, base):
        self.host, self.port = parse_local_base(base)

    def request(self, method, path, body=None, token=None):
        payload = json.dumps(body, ensure_ascii=True).encode("utf-8") if body is not None else None
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        connection = http.client.HTTPConnection(self.host, self.port, timeout=local_http_timeout())
        try:
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            raw = response.read()
        finally:
            connection.close()
        try:
            decoded = json.loads(raw.decode("utf-8")) if raw else {}
        except json.JSONDecodeError as error:
            raise RuntimeError(f"{method} {path} 返回了无效 JSON (HTTP {response.status})") from error
        if response.status >= 400 or decoded.get("code") != 0:
            raise RuntimeError(f"{method} {path} 失败 (HTTP {response.status}): {decoded.get('message', 'unknown error')}")
        return decoded.get("data")


def merchant_token(api, merchant, password):
    data = api.request("POST", "/api/auth/login", {
        "username": merchant, "password": password, "role": "MERCHANT",
    })
    token = data.get("accessToken") if isinstance(data, dict) else None
    if not token:
        raise RuntimeError("商家登录没有返回 accessToken")
    return token


def prepare(args):
    run_id = require_run_id(args.run_id)
    if args.coupons_per_user < 1:
        raise ValueError("coupons-per-user 必须至少为 1")
    api = LocalApi(args.base)
    token = merchant_token(api, args.merchant, args.password)
    fixture = api.request("POST", "/api/perf/seckill/fixtures", {
        "runId": run_id,
        "userCount": args.users,
        "shopCount": args.shops,
        "couponCount": args.coupons,
        "stockPerCoupon": args.stock,
    }, token)
    users = fixture.get("users", [])
    if len(users) != args.users:
        raise RuntimeError("夹具用户数与请求不一致")

    destination = run_dir(DATA_ROOT, run_id)
    destination.mkdir(parents=True, exist_ok=False)
    coupon_ids = fixture.get("couponIds", [])
    if not coupon_ids or args.coupons_per_user > len(coupon_ids):
        raise RuntimeError("每用户请求的优惠券数超过已创建夹具")

    request_file = destination / "seckill-requests.csv"
    with request_file.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        for user in users:
            if args.coupons_per_user == 1:
                selected_coupon_ids = (user["couponId"],)
            else:
                # A user may validly claim once from each distinct coupon. This creates
                # unique (userId, couponId) pairs without weakening one-user-one-coupon.
                selected_coupon_ids = coupon_ids[:args.coupons_per_user]
            for coupon_id in selected_coupon_ids:
                writer.writerow((user["accessToken"], coupon_id, user["deviceFingerprint"], user["username"]))
    os.chmod(request_file, 0o600)
    manifest = {
        "run_id": run_id,
        "base_url": args.base,
        "users": len(users),
        "shops": len(fixture.get("merchantIds", [])),
        "coupon_ids": coupon_ids,
        "stock_per_coupon": args.stock,
        "coupons_per_user": args.coupons_per_user,
        "request_count": len(users) * args.coupons_per_user,
        "request_file": str(request_file.relative_to(ROOT)),
    }
    (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def cleanup(args):
    run_id = require_run_id(args.run_id)
    api = LocalApi(args.base)
    token = merchant_token(api, args.merchant, args.password)
    result = api.request("DELETE", "/api/perf/seckill/fixtures", {"runId": run_id}, token)
    print(json.dumps(result, ensure_ascii=False, indent=2))


def percentile(values, ratio):
    if not values:
        return 0.0
    values = sorted(values)
    return round(values[max(0, math.ceil(len(values) * ratio) - 1)], 2)


def summarize(args):
    run_id = require_run_id(args.run_id)
    jtl = require_file_under(args.jtl, run_dir(RESULT_ROOT, run_id))
    rows = list(csv.DictReader(jtl.open(encoding="utf-8-sig", newline="")))
    if not rows:
        raise RuntimeError("JTL 没有样本")
    elapsed = [float(row["elapsed"]) for row in rows]
    starts = [float(row["timeStamp"]) for row in rows]
    ends = [start + duration for start, duration in zip(starts, elapsed)]
    duration_seconds = (max(ends) - min(starts)) / 1000
    api_success = [row for row in rows if row.get("success", "").lower() == "true"]
    business_success = [row for row in rows if row.get("business_success", "").lower() == "true"]
    rejection_counts = {}
    for row in rows:
        if row.get("success", "").lower() == "true" and row.get("business_success", "").lower() != "true":
            reason = row.get("business_message") or "UNKNOWN_BUSINESS_REJECTION"
            rejection_counts[reason] = rejection_counts.get(reason, 0) + 1
    summary = {
        "run_id": run_id,
        "samples": len(rows),
        "api_success_requests": len(api_success),
        "business_success_requests": len(business_success),
        "business_rejected_requests": len(api_success) - len(business_success),
        "transport_or_api_error_requests": len(rows) - len(api_success),
        "error_rate": round((len(rows) - len(api_success)) / len(rows), 6),
        "duration_seconds": round(duration_seconds, 3),
        "throughput_qps": round(len(rows) / duration_seconds, 2) if duration_seconds else 0.0,
        "average_ms": round(sum(elapsed) / len(elapsed), 2),
        "p50_ms": percentile(elapsed, 0.50),
        "p95_ms": percentile(elapsed, 0.95),
        "p99_ms": percentile(elapsed, 0.99),
        "max_ms": round(max(elapsed), 2),
        "business_rejections": rejection_counts,
    }
    output = run_dir(RESULT_ROOT, run_id) / "summary.json"
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def verify(args):
    run_id = require_run_id(args.run_id)
    request_file = require_file_under(run_dir(DATA_ROOT, run_id) / "seckill-requests.csv", run_dir(DATA_ROOT, run_id))
    jtl = require_file_under(args.jtl, run_dir(RESULT_ROOT, run_id))
    samples = list(csv.DictReader(jtl.open(encoding="utf-8-sig", newline="")))
    accepted = [row for row in samples if row.get("business_success", "").lower() == "true" and row.get("order_no")]
    if not accepted:
        raise RuntimeError("JTL 中没有业务成功的订单号")
    accepted_by_coupon = Counter(str(row.get("coupon_id", "")) for row in accepted)
    api = LocalApi(args.base)
    deadline = time.monotonic() + args.timeout_seconds
    audit = None
    while time.monotonic() < deadline:
        audit = api.request("GET", "/api/perf/seckill/fixtures/" + run_id + "/audit",
                            token=merchant_token(api, args.merchant, args.password))
        if audit_matches(audit, accepted_by_coupon, len(accepted)):
            break
        if audit.get("pendingCount", 0) or audit.get("orderCount", 0) < len(accepted):
            time.sleep(0.5)
    if audit is None or not audit_matches(audit, accepted_by_coupon, len(accepted)):
        raise RuntimeError("异步订单、库存或 pending 状态未在时限内收敛")
    verification = {
        "run_id": run_id,
        "accepted_requests": len(accepted),
        "system_error_requests": sum(row.get("success", "").lower() != "true" for row in samples),
        "audit": audit,
        "stock_consistent": True,
        "no_negative_stock": True,
        "no_duplicate_claim": True,
        "pending_drained": True,
    }
    output = run_dir(RESULT_ROOT, run_id) / "verification.json"
    output.write_text(json.dumps(verification, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(verification, ensure_ascii=False, indent=2))


def audit_matches(audit, accepted_by_coupon, accepted_total):
    if audit.get("orderCount") != accepted_total or audit.get("pendingCount") != 0:
        return False
    if audit.get("duplicateClaimPairs") != 0:
        return False
    coupons = audit.get("coupons")
    if not isinstance(coupons, list):
        return False
    for coupon in coupons:
        coupon_id = str(coupon.get("couponId", ""))
        accepted = accepted_by_coupon.get(coupon_id, 0)
        initial = coupon.get("initialStock")
        remaining = coupon.get("remainingStock")
        if (not isinstance(initial, int) or not isinstance(remaining, int) or remaining < 0
                or initial - remaining != accepted or coupon.get("claimantCount") != accepted
                or coupon.get("pendingCount") != 0 or coupon.get("orderCount") != accepted):
            return False
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--merchant", default="merchant_food")
    parser.add_argument("--password", default=os.environ.get("PERF_MERCHANT_PASSWORD"))
    commands = parser.add_subparsers(dest="command", required=True)

    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--run-id", required=True)
    prepare_parser.add_argument("--users", type=int, default=200)
    prepare_parser.add_argument("--shops", type=int, default=1)
    prepare_parser.add_argument("--coupons", type=int, default=1)
    prepare_parser.add_argument("--stock", type=int, default=200)
    prepare_parser.add_argument("--coupons-per-user", type=int, default=1)
    prepare_parser.set_defaults(func=prepare)

    cleanup_parser = commands.add_parser("cleanup")
    cleanup_parser.add_argument("--run-id", required=True)
    cleanup_parser.set_defaults(func=cleanup)

    summarize_parser = commands.add_parser("summarize")
    summarize_parser.add_argument("--run-id", required=True)
    summarize_parser.add_argument("--jtl", required=True)
    summarize_parser.set_defaults(func=summarize)

    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--run-id", required=True)
    verify_parser.add_argument("--jtl", required=True)
    verify_parser.add_argument("--timeout-seconds", type=int, default=30)
    verify_parser.set_defaults(func=verify)

    args = parser.parse_args()
    if not args.password:
        parser.error("请通过 PERF_MERCHANT_PASSWORD 环境变量或 --password 提供商家密码")
    try:
        args.func(args)
    except (OSError, ValueError, RuntimeError, http.client.HTTPException) as error:
        print("error: " + str(error), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
