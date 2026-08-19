#!/usr/bin/env python3
"""可复现的秒杀实验矩阵：吞吐、延迟、售罄一致性和一人一单。"""

import argparse
import http.client
import json
import math
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import urlparse


parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://127.0.0.1:8080")
parser.add_argument("--concurrency", default="10,50,100,200")
parser.add_argument("--requests", type=int, default=400)
parser.add_argument("--merchant", default="merchant_food")
parser.add_argument("--password", default="123456")
parser.add_argument("--output", default="")
args = parser.parse_args()

parsed = urlparse(args.base)
HOST = parsed.hostname or "127.0.0.1"
PORT = parsed.port or 80
CONCURRENCY_LEVELS = [int(value) for value in args.concurrency.split(",") if value.strip()]
RUN_ID = datetime.now().strftime("%Y%m%d%H%M%S")
local = threading.local()


def connection():
    conn = getattr(local, "connection", None)
    if conn is None:
        conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
        local.connection = conn
    return conn


def api(method, path, body=None, token=None, retry=True):
    payload = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        conn = connection()
        conn.request(method, path, body=payload, headers=headers)
        response = conn.getresponse()
        raw = response.read()
        data = json.loads(raw.decode("utf-8")) if raw else {}
        data["_http_status"] = response.status
        return data
    except (ConnectionError, OSError, http.client.HTTPException):
        try:
            connection().close()
        except Exception:
            pass
        local.connection = None
        if retry:
            return api(method, path, body, token, False)
        raise


def percentile(values, ratio):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(len(ordered) * ratio) - 1))
    return ordered[index]


def merchant_login():
    result = api("POST", "/api/auth/login", {
        "username": args.merchant,
        "password": args.password,
        "role": "MERCHANT",
    })
    token = result.get("accessToken")
    if not token:
        raise RuntimeError("商户登录失败: " + str(result))
    return token


def provision_user(index):
    username = f"bench_{RUN_ID}_{index:04d}"
    body = {"username": username, "password": args.password, "role": "USER"}
    api("POST", "/api/auth/register", body)
    login = api("POST", "/api/auth/login", body)
    return {"username": username, "token": login.get("accessToken", "")}


def provision_users(count):
    print(f"[setup] creating and logging in {count} isolated users...")
    users = []
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=min(24, count)) as pool:
        futures = [pool.submit(provision_user, index) for index in range(count)]
        for future in as_completed(futures):
            user = future.result()
            if user["token"]:
                users.append(user)
    if len(users) != count:
        raise RuntimeError(f"only {len(users)}/{count} benchmark users logged in")
    print(f"[setup] users ready in {time.perf_counter() - started:.2f}s")
    return users


def create_coupon(merchant_token, name, stock):
    now = datetime.now()
    result = api("POST", "/api/coupon/create", {
        "couponName": name,
        "couponDesc": "isolated benchmark fixture",
        "discountAmount": 1,
        "totalStock": stock,
        "remainStock": stock,
        "startTime": (now - timedelta(minutes=1)).isoformat(timespec="seconds"),
        "endTime": (now + timedelta(hours=2)).isoformat(timespec="seconds"),
        "perUserMax": 1,
        "status": 1,
    }, merchant_token)
    if not result.get("id"):
        raise RuntimeError("创建压测优惠券失败: " + str(result))
    return result["id"]


def execute_request(coupon_id, user, request_index, same_device=False):
    started = time.perf_counter()
    try:
        response = api("POST", "/api/seckill/execute", {
            "couponId": coupon_id,
            "deviceFingerprint": "bench-shared" if same_device else f"bench-{RUN_ID}-{coupon_id}-{request_index}",
        }, user["token"])
        elapsed_ms = (time.perf_counter() - started) * 1000
        return {
            "latency_ms": elapsed_ms,
            "success": bool(response.get("success")),
            "message": response.get("message", ""),
            "order_no": response.get("orderNo"),
            "user": user,
            "http_status": response.get("_http_status"),
        }
    except Exception as error:
        return {
            "latency_ms": (time.perf_counter() - started) * 1000,
            "success": False,
            "message": type(error).__name__ + ": " + str(error),
            "order_no": None,
            "user": user,
            "http_status": 0,
        }


def run_load(coupon_id, users, concurrency, requests, same_user=False):
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = []
        for index in range(requests):
            user = users[0] if same_user else users[index % len(users)]
            futures.append(pool.submit(execute_request, coupon_id, user, index))
        rows = [future.result() for future in as_completed(futures)]
    duration = time.perf_counter() - started
    return summarize(rows, concurrency, requests, duration)


def summarize(rows, concurrency, requests, duration):
    latencies = [row["latency_ms"] for row in rows]
    successes = [row for row in rows if row["success"]]
    errors = {}
    for row in rows:
        if not row["success"]:
            key = row["message"] or f"HTTP {row['http_status']}"
            errors[key] = errors.get(key, 0) + 1
    order_numbers = [row["order_no"] for row in successes if row["order_no"]]
    return {
        "concurrency": concurrency,
        "requests": requests,
        "success": len(successes),
        "failed": len(rows) - len(successes),
        "success_rate_pct": round(len(successes) * 100 / len(rows), 2) if rows else 0,
        "duration_s": round(duration, 4),
        "throughput_rps": round(len(rows) / duration, 2) if duration else 0,
        "latency_avg_ms": round(statistics.fmean(latencies), 2) if latencies else 0,
        "latency_p50_ms": round(percentile(latencies, 0.50), 2),
        "latency_p95_ms": round(percentile(latencies, 0.95), 2),
        "latency_p99_ms": round(percentile(latencies, 0.99), 2),
        "latency_max_ms": round(max(latencies), 2) if latencies else 0,
        "unique_order_numbers": len(set(order_numbers)),
        "duplicate_order_numbers": len(order_numbers) - len(set(order_numbers)),
        "errors": errors,
        "_success_rows": successes,
    }


def verify_orders(result, timeout_seconds=30):
    pending = {row["order_no"]: row for row in result.pop("_success_rows") if row["order_no"]}
    deadline = time.time() + timeout_seconds
    visible = 0
    while pending and time.time() < deadline:
        completed = []
        with ThreadPoolExecutor(max_workers=min(40, len(pending))) as pool:
            futures = {
                pool.submit(api, "GET", "/api/seckill/result/" + order_no, None, row["user"]["token"]): order_no
                for order_no, row in pending.items()
            }
            for future in as_completed(futures):
                order_no = futures[future]
                response = future.result()
                if response.get("success") and response.get("status") == "CREATED":
                    completed.append(order_no)
        for order_no in completed:
            pending.pop(order_no, None)
            visible += 1
        if pending:
            time.sleep(0.3)
    result["orders_visible_in_mysql"] = visible
    result["orders_missing_after_timeout"] = len(pending)


def merchant_stock(merchant_token, coupon_id):
    shop = api("GET", "/api/merchant/me", None, merchant_token)
    for coupon in shop.get("coupons", []):
        if coupon.get("id") == coupon_id:
            return coupon.get("remainStock")
    return None


merchant_token = merchant_login()
max_users = max(args.requests, 300)
users = provision_users(max_users)

warmup_coupon = create_coupon(merchant_token, f"BENCH-WARMUP-{RUN_ID}", 30)
warmup = run_load(warmup_coupon, users[:30], 10, 30)
verify_orders(warmup)
print(f"[warmup] {warmup['throughput_rps']} req/s, excluded from matrix")

report = {
    "run_id": RUN_ID,
    "timestamp": datetime.now().isoformat(timespec="seconds"),
    "base_url": args.base,
    "requests_per_level": args.requests,
    "concurrency_levels": CONCURRENCY_LEVELS,
    "warmup": warmup,
    "throughput_matrix": [],
}

for concurrency in CONCURRENCY_LEVELS:
    coupon_id = create_coupon(merchant_token, f"BENCH-C{concurrency}-{RUN_ID}", args.requests)
    result = run_load(coupon_id, users, concurrency, args.requests)
    verify_orders(result)
    result["coupon_id"] = coupon_id
    result["redis_remain_after"] = merchant_stock(merchant_token, coupon_id)
    result["stock_expected_after"] = args.requests - result["success"]
    result["stock_consistent"] = result["redis_remain_after"] == result["stock_expected_after"]
    report["throughput_matrix"].append(result)
    print(f"[C={concurrency:>3}] QPS={result['throughput_rps']:>7.2f} "
          f"P50={result['latency_p50_ms']:>7.2f}ms P99={result['latency_p99_ms']:>7.2f}ms "
          f"success={result['success']}/{args.requests} stock={result['redis_remain_after']}")

exhaustion_stock = 100
exhaustion_requests = 300
exhaustion_coupon = create_coupon(merchant_token, f"BENCH-EXHAUST-{RUN_ID}", exhaustion_stock)
exhaustion = run_load(exhaustion_coupon, users, 100, exhaustion_requests)
verify_orders(exhaustion)
exhaustion["coupon_id"] = exhaustion_coupon
exhaustion["initial_stock"] = exhaustion_stock
exhaustion["redis_remain_after"] = merchant_stock(merchant_token, exhaustion_coupon)
exhaustion["no_oversell"] = exhaustion["success"] == exhaustion_stock and exhaustion["redis_remain_after"] == 0
report["stock_exhaustion"] = exhaustion
print(f"[exhaustion] success={exhaustion['success']}/{exhaustion_requests}, "
      f"remain={exhaustion['redis_remain_after']}, no_oversell={exhaustion['no_oversell']}")

duplicate_coupon = create_coupon(merchant_token, f"BENCH-ONE-USER-{RUN_ID}", 100)
duplicate = run_load(duplicate_coupon, users[:1], 50, 100, same_user=True)
verify_orders(duplicate)
duplicate["coupon_id"] = duplicate_coupon
duplicate["redis_remain_after"] = merchant_stock(merchant_token, duplicate_coupon)
duplicate["one_user_one_order"] = duplicate["success"] == 1 and duplicate["redis_remain_after"] == 99
report["same_user_race"] = duplicate
print(f"[same-user] success={duplicate['success']}/100, remain={duplicate['redis_remain_after']}, "
      f"one_user_one_order={duplicate['one_user_one_order']}")

if args.output:
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("[output] " + str(output.resolve()))
else:
    print(json.dumps(report, ensure_ascii=False, indent=2))
