#!/usr/bin/env python3
"""Prepare isolated JMeter fixtures and summarize JTL results without leaking tokens."""

import argparse
import csv
import http.client
import json
import math
import statistics
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import urlparse


def api_factory(base_url):
    parsed = urlparse(base_url)
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or 80
    local = threading.local()

    def request(method, path, body=None, token=None, retry=True):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        try:
            conn = getattr(local, "connection", None)
            if conn is None:
                conn = http.client.HTTPConnection(host, port, timeout=20)
                local.connection = conn
            conn.request(method, path, body=payload, headers=headers)
            response = conn.getresponse()
            raw = response.read()
            data = json.loads(raw.decode("utf-8")) if raw else {}
            data["_http_status"] = response.status
            return data
        except (ConnectionError, OSError, http.client.HTTPException):
            conn = getattr(local, "connection", None)
            if conn:
                try:
                    conn.close()
                except Exception:
                    pass
            local.connection = None
            if retry:
                return request(method, path, body, token, False)
            raise

    return request


def percentile(values, ratio):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(len(ordered) * ratio) - 1))
    return round(ordered[index], 2)


def prepare_suite(args):
    api = api_factory(args.base)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    run_id = datetime.now().strftime("%Y%m%d%H%M%S")

    merchant = api("POST", "/api/auth/login", {
        "username": args.merchant,
        "password": args.password,
        "role": "MERCHANT",
    })
    merchant_token = merchant.get("accessToken")
    if not merchant_token:
        raise RuntimeError("merchant login failed: " + str(merchant))

    def provision(index):
        username = f"jmeter_{run_id}_{index:05d}"
        credentials = {"username": username, "password": args.password, "role": "USER"}
        registered = api("POST", "/api/auth/register", credentials)
        if registered.get("success") is False and registered.get("message") != "用户名已存在":
            raise RuntimeError("register failed: " + str(registered))
        login = api("POST", "/api/auth/login", credentials)
        token = login.get("accessToken")
        if not token:
            raise RuntimeError("user login failed: " + str(login))
        return {"username": username, "token": token}

    print(f"[setup] provisioning {args.users} users")
    users = [None] * args.users
    with ThreadPoolExecutor(max_workers=min(args.setup_workers, args.users)) as pool:
        futures = {pool.submit(provision, index): index for index in range(args.users)}
        completed = 0
        for future in as_completed(futures):
            index = futures[future]
            users[index] = future.result()
            completed += 1
            if completed % 500 == 0 or completed == args.users:
                print(f"[setup] users {completed}/{args.users}")

    def create_coupon(name, stock):
        now = datetime.now()
        result = api("POST", "/api/coupon/create", {
            "couponName": name,
            "couponDesc": "JMeter isolated benchmark fixture",
            "discountAmount": 1,
            "totalStock": stock,
            "remainStock": stock,
            "startTime": (now - timedelta(minutes=1)).isoformat(timespec="seconds"),
            "endTime": (now + timedelta(hours=2)).isoformat(timespec="seconds"),
            "perUserMax": 1,
            "status": 1,
        }, merchant_token)
        if not result.get("id"):
            raise RuntimeError("coupon creation failed: " + str(result))
        return result["id"]

    scenarios = {}

    def write_scenario(name, request_count, coupon_stocks, user_count=None, same_user=False):
        coupon_ids = [create_coupon(f"JMETER-{name.upper()}-{run_id}-{i + 1}", stock)
                      for i, stock in enumerate(coupon_stocks)]
        selected = users[:user_count] if user_count else users
        rows = []
        for index in range(request_count):
            user = selected[0] if same_user else selected[index % len(selected)]
            coupon_index = min(len(coupon_ids) - 1, index // max(1, len(selected)))
            rows.append((user["token"], coupon_ids[coupon_index],
                         f"jmeter-{run_id}-{name}-{index}-{uuid.uuid4().hex[:8]}", user["username"]))
        csv_path = output / f"{name}.csv"
        with csv_path.open("w", encoding="utf-8", newline="") as handle:
            csv.writer(handle).writerows(rows)
        scenarios[name] = {
            "requests": request_count,
            "coupon_ids": coupon_ids,
            "initial_stocks": coupon_stocks,
            "data_file": str(csv_path.resolve()),
        }
        print(f"[setup] {name}: {request_count} rows, coupons={coupon_ids}")

    for threads in (50, 100, 200, 400):
        write_scenario(f"stair_{threads}", 1200, [1200], user_count=1200)

    stability_requests = args.stability_seconds * args.stability_rps
    stability_coupon_count = math.ceil(stability_requests / args.users)
    stability_stocks = []
    for index in range(stability_coupon_count):
        stability_stocks.append(min(args.users, stability_requests - index * args.users))
    write_scenario("stability", stability_requests, stability_stocks)
    write_scenario("exhaustion", 1000, [100], user_count=1000)
    write_scenario("same_user", 100, [100], user_count=1, same_user=True)
    write_scenario("mq_backlog", 1000, [1000], user_count=1000)

    manifest = {
        "run_id": run_id,
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "base_url": args.base,
        "jmeter_version": "5.6.3",
        "user_count": args.users,
        "stability": {
            "threads": 200,
            "target_rps": args.stability_rps,
            "duration_seconds": args.stability_seconds,
        },
        "merchant_token": merchant_token,
        "scenarios": scenarios,
    }
    manifest_path = output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print("[setup] manifest=" + str(manifest_path.resolve()))


def summarize(args):
    jtl = Path(args.jtl)
    rows = list(csv.DictReader(jtl.open(encoding="utf-8-sig", newline="")))
    if not rows:
        raise RuntimeError("empty JTL: " + str(jtl))
    elapsed = [float(row["elapsed"]) for row in rows]
    timestamps = [float(row["timeStamp"]) for row in rows]
    ends = [stamp + duration for stamp, duration in zip(timestamps, elapsed)]
    duration_seconds = (max(ends) - min(timestamps)) / 1000
    business_success = [row for row in rows if row.get("business_success", "").lower() == "true"]
    order_numbers = [row.get("order_no", "") for row in business_success if row.get("order_no")]
    errors = {}
    for row in rows:
        if row.get("business_success", "").lower() != "true":
            if row.get("success", "").lower() != "true":
                message = row.get("responseCode") or row.get("responseMessage") or "HTTP failure"
            else:
                message = row.get("business_message") or "business failure"
            errors[message] = errors.get(message, 0) + 1
    summary = {
        "scenario": args.scenario,
        "timestamp": datetime.now().isoformat(timespec="seconds"),
        "samples": len(rows),
        "http_success": sum(row.get("success", "").lower() == "true" for row in rows),
        "business_success": len(business_success),
        "business_failed": len(rows) - len(business_success),
        "duration_s": round(duration_seconds, 3),
        "throughput_rps": round(len(rows) / duration_seconds, 2),
        "latency_avg_ms": round(statistics.fmean(elapsed), 2),
        "latency_p50_ms": percentile(elapsed, 0.50),
        "latency_p95_ms": percentile(elapsed, 0.95),
        "latency_p99_ms": percentile(elapsed, 0.99),
        "latency_max_ms": round(max(elapsed), 2),
        "unique_order_numbers": len(set(order_numbers)),
        "duplicate_order_numbers": len(order_numbers) - len(set(order_numbers)),
        "errors": errors,
    }
    destination = Path(args.output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def prepare_capacity(args):
    """Refresh existing isolated users and create independent fixtures for capacity probing."""
    api = api_factory(args.base)
    source_rows = list(csv.reader(Path(args.source).open(encoding="utf-8", newline="")))
    usernames = []
    seen = set()
    for row in source_rows:
        if len(row) >= 4 and row[3] not in seen:
            seen.add(row[3])
            usernames.append(row[3])
    if len(usernames) < args.users:
        raise RuntimeError(f"source only contains {len(usernames)} unique users, need {args.users}")
    usernames = usernames[:args.users]

    def login_user(index):
        username = usernames[index]
        login = api("POST", "/api/auth/login", {
            "username": username,
            "password": args.password,
            "role": "USER",
        })
        token = login.get("accessToken")
        if not token:
            raise RuntimeError("user login failed: " + str(login))
        return {"username": username, "token": token}

    print(f"[capacity] refreshing {len(usernames)} users")
    users = [None] * len(usernames)
    with ThreadPoolExecutor(max_workers=min(args.setup_workers, len(usernames))) as pool:
        futures = {pool.submit(login_user, index): index for index in range(len(usernames))}
        completed = 0
        for future in as_completed(futures):
            users[futures[future]] = future.result()
            completed += 1
            if completed % 500 == 0 or completed == len(users):
                print(f"[capacity] users {completed}/{len(users)}")

    merchant = api("POST", "/api/auth/login", {
        "username": args.merchant,
        "password": args.password,
        "role": "MERCHANT",
    })
    merchant_token = merchant.get("accessToken")
    if not merchant_token:
        raise RuntimeError("merchant login failed: " + str(merchant))
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    run_id = datetime.now().strftime("%Y%m%d%H%M%S")

    def create_coupon(name, stock):
        now = datetime.now()
        result = api("POST", "/api/coupon/create", {
            "couponName": name,
            "couponDesc": "JMeter capacity probe fixture",
            "discountAmount": 1,
            "totalStock": stock,
            "remainStock": stock,
            "startTime": (now - timedelta(minutes=1)).isoformat(timespec="seconds"),
            "endTime": (now + timedelta(hours=2)).isoformat(timespec="seconds"),
            "perUserMax": 1,
            "status": 1,
        }, merchant_token)
        if not result.get("id"):
            raise RuntimeError("coupon creation failed: " + str(result))
        return result["id"]

    manifest = {"run_id": run_id, "levels": {}, "loops_per_thread": args.loops_per_thread}
    for threads in [int(value) for value in args.levels.split(",") if value.strip()]:
        request_count = threads * args.loops_per_thread
        coupon_count = math.ceil(request_count / len(users))
        stocks = [min(len(users), request_count - index * len(users)) for index in range(coupon_count)]
        coupon_ids = [create_coupon(f"JMETER-CAP-{threads}-{run_id}-{index + 1}", stock)
                      for index, stock in enumerate(stocks)]
        path = output / f"capacity_{threads}.csv"
        with path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle)
            for index in range(request_count):
                user = users[index % len(users)]
                coupon_id = coupon_ids[index // len(users)]
                writer.writerow((user["token"], coupon_id,
                                 f"capacity-{run_id}-{threads}-{index}-{uuid.uuid4().hex[:8]}",
                                 user["username"]))
        manifest["levels"][str(threads)] = {
            "threads": threads,
            "requests": request_count,
            "coupon_ids": coupon_ids,
            "initial_stocks": stocks,
            "data_file": str(path.resolve()),
        }
        print(f"[capacity] threads={threads} requests={request_count} coupons={coupon_ids}")
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare-suite")
    prepare.add_argument("--base", default="http://127.0.0.1:8080")
    prepare.add_argument("--merchant", default="merchant_food")
    prepare.add_argument("--password", default="123456")
    prepare.add_argument("--users", type=int, default=4000)
    prepare.add_argument("--setup-workers", type=int, default=32)
    prepare.add_argument("--stability-seconds", type=int, default=600)
    prepare.add_argument("--stability-rps", type=int, default=200)
    prepare.add_argument("--output", default="target/jmeter-data")
    prepare.set_defaults(func=prepare_suite)

    report = subparsers.add_parser("summarize")
    report.add_argument("--scenario", required=True)
    report.add_argument("--jtl", required=True)
    report.add_argument("--output", required=True)
    report.set_defaults(func=summarize)

    capacity = subparsers.add_parser("prepare-capacity")
    capacity.add_argument("--base", default="http://127.0.0.1:8080")
    capacity.add_argument("--merchant", default="merchant_food")
    capacity.add_argument("--password", default="123456")
    capacity.add_argument("--source", default="target/jmeter-data/stability.csv")
    capacity.add_argument("--users", type=int, default=4000)
    capacity.add_argument("--setup-workers", type=int, default=32)
    capacity.add_argument("--levels", default="100,200,400,800,1200,1600")
    capacity.add_argument("--loops-per-thread", type=int, default=10)
    capacity.add_argument("--output", default="target/jmeter-capacity")
    capacity.set_defaults(func=prepare_capacity)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
