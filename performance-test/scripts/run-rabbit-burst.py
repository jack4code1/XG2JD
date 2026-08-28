#!/usr/bin/env python3
"""Run a local burst through the real Lua acceptance and RabbitMQ order path."""

import argparse
import csv
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

from fixtures import LocalApi, RESULT_ROOT, merchant_token, require_run_id, run_dir


ROOT = Path(__file__).resolve().parents[1]
ORDER_NO = re.compile(r"\d{13}[0-9a-f]{8}\Z")
ALLOWED_THREADS = {100, 200, 500, 1000}


def run(command, environment=None):
    # COSEC: every executable and argument is fixed by this script or validated numeric/run-id input.
    subprocess.run(command, check=True, env=environment)


def api_request(api, token, method, path, body=None):
    return api.request(method, path, body=body, token=token)


def monitor(api, token, stop, samples):
    while not stop.is_set():
        try:
            snapshot = api_request(api, token, "GET", "/api/perf/seckill/runtime")
            snapshot["observedAt"] = time.monotonic()
            samples.append(snapshot)
        except Exception as error:  # Keep the load result; monitoring faults are reported separately.
            samples.append({"observedAt": time.monotonic(), "monitorError": str(error)})
        stop.wait(0.1)


def percentile(values, ratio):
    if not values:
        return 0
    values = sorted(values)
    return values[max(0, int(__import__("math").ceil(len(values) * ratio) - 1))]


def wait_for_drain(api, token, run_id, accepted, timeout_seconds):
    deadline = time.monotonic() + timeout_seconds
    last_audit = None
    while time.monotonic() < deadline:
        last_audit = api_request(api, token, "GET", f"/api/perf/seckill/fixtures/{run_id}/audit")
        runtime = api_request(api, token, "GET", "/api/perf/seckill/runtime")
        if (last_audit.get("orderCount") == accepted and last_audit.get("pendingCount") == 0
                and runtime.get("queueReady") == 0 and runtime.get("deadLetterReady") == 0):
            return last_audit, runtime, time.monotonic()
        time.sleep(0.2)
    raise RuntimeError(f"订单未在 {timeout_seconds}s 内完成: {last_audit}")


def cleanup_test_orphans(jtl_path):
    rows = csv.DictReader(jtl_path.open(encoding="utf-8-sig", newline=""))
    order_nos = {row.get("order_no", "") for row in rows}
    for order_no in order_nos:
        if not ORDER_NO.fullmatch(order_no):
            continue
        subprocess.run(["redis-cli", "del", "seckill:pending:order:" + order_no], check=True,
                       stdout=subprocess.DEVNULL)
        subprocess.run(["redis-cli", "zrem", "seckill:pending:orders", order_no], check=True,
                       stdout=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--threads", type=int, default=1000)
    parser.add_argument("--users", type=int, default=5000)
    parser.add_argument("--stock", type=int, default=10000)
    parser.add_argument("--ramp-up", type=int, default=1)
    parser.add_argument("--duration", type=int, default=15)
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--password", default=os.environ.get("PERF_MERCHANT_PASSWORD"))
    args = parser.parse_args()
    run_id = require_run_id(args.run_id)
    if not args.password:
        parser.error("请通过 PERF_MERCHANT_PASSWORD 或 --password 提供商家密码")
    if args.threads not in ALLOWED_THREADS or not 1 <= args.users <= 5000:
        parser.error("threads 必须为 100/200/500/1000，users 必须为 1-5000")
    if not args.users < args.stock <= 100000 or not 1 <= args.ramp_up <= 30 or not 1 <= args.duration <= 120:
        parser.error("stock、ramp-up 或 duration 超出允许范围")

    api = LocalApi(args.base)
    token = merchant_token(api, "merchant_food", args.password)
    result_dir = run_dir(RESULT_ROOT, run_id)
    result_dir.mkdir(parents=True, exist_ok=False)
    jtl_path = result_dir / "acceptance.jtl"

    try:
        run([sys.executable, str(ROOT / "scripts" / "fixtures.py"), "--base", args.base,
             "prepare", "--run-id", run_id, "--users", str(args.users), "--shops", "1",
             "--coupons", "1", "--stock", str(args.stock)])
        api_request(api, token, "POST", "/api/perf/seckill/runtime/reset")

        stop = threading.Event()
        samples = []
        watcher = threading.Thread(target=monitor, args=(api, token, stop, samples), daemon=True)
        watcher.start()
        started_at = time.monotonic()
        jmeter_environment = os.environ.copy()
        jmeter_environment.setdefault("JVM_ARGS", "-Xms512m -Xmx512m -XX:+UseG1GC")
        run(["jmeter", "-n", "-t", str(ROOT / "jmeter" / "seckill-smoke.jmx"), "-l", str(jtl_path),
             "-JbaseUrl=" + args.base, "-JdataFile=" + str(ROOT / "data" / run_id / "seckill-requests.csv"),
             "-Jthreads=" + str(args.threads), "-JrampUp=" + str(args.ramp_up),
             "-Jduration=" + str(args.duration),
             "-Jsample_variables=api_success,business_success,business_message,order_no,username,coupon_id"],
            jmeter_environment)
        run([sys.executable, str(ROOT / "scripts" / "fixtures.py"), "--base", args.base,
             "summarize", "--run-id", run_id, "--jtl", str(jtl_path)])
        summary = json.loads((result_dir / "summary.json").read_text(encoding="utf-8"))
        audit, final_runtime, drained_at = wait_for_drain(api, token, run_id,
                                                          summary["business_success_requests"], args.timeout_seconds)
        # Allow the delayed pending-index confirmation update to settle before assessing compensation.
        time.sleep(6)
        final_audit = api_request(api, token, "GET", f"/api/perf/seckill/fixtures/{run_id}/audit")
        stop.set()
        watcher.join(timeout=2)

        queue_values = [sample.get("queueReady", 0) for sample in samples if "queueReady" in sample]
        hikari_active = [sample.get("hikari", {}).get("active", 0) for sample in samples if "hikari" in sample]
        hikari_waiting = [sample.get("hikari", {}).get("waiting", 0) for sample in samples if "hikari" in sample]
        consumer = final_runtime.get("consumer", {})
        elapsed_to_drain = drained_at - started_at
        consumer_attempts = consumer.get("count", 0)
        report = {
            "run_id": run_id,
            "http": summary,
            "producer_rate_qps": summary["business_success_requests"] / summary["duration_seconds"],
            "consumer": consumer,
            "duplicate_or_retry_delivery_attempts": consumer.get("duplicates", max(
                0, consumer_attempts - summary["business_success_requests"])),
            "consumer_failed_attempts": consumer.get("failed", 0),
            "dead_letter_ready": final_runtime.get("deadLetterReady", -1),
            "consumer_tps_to_drain": round(summary["business_success_requests"] / elapsed_to_drain, 2),
            "max_queue_ready": max(queue_values, default=-1),
            "max_hikari_active": max(hikari_active, default=-1),
            "max_hikari_waiting": max(hikari_waiting, default=-1),
            "hikari_final": final_runtime.get("hikari", {}),
            "drain_seconds_from_burst_start": round(elapsed_to_drain, 3),
            "audit_at_drain": audit,
            "audit_after_confirmation_grace": final_audit,
            "pending_compensation_triggered": final_audit.get("pendingOrderIndexCount", 0) > 0,
            "monitor_samples": len(samples),
            "monitor_errors": sum("monitorError" in sample for sample in samples),
        }
        (result_dir / "rabbit-burst.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
    finally:
        stop = locals().get("stop")
        if stop:
            stop.set()
        if jtl_path.is_file():
            cleanup_test_orphans(jtl_path)
        # The test fixture cleaner remains scoped to the validated run-id prefix.
        subprocess.run([sys.executable, str(ROOT / "scripts" / "fixtures.py"), "--base", args.base,
                        "cleanup", "--run-id", run_id], check=False)


if __name__ == "__main__":
    main()
