#!/usr/bin/env python3
"""Run reproducible P0 cache and Lua stability benchmarks against localhost only."""

import argparse
import csv
import hashlib
import http.client
import json
import math
import os
import platform
import re
import statistics
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
CACHE_ROOT = ROOT / "target" / "perf-cache"
LUA_ROOT = ROOT / "performance-test" / "results"
RUN_ID = re.compile(r"[A-Za-z0-9_-]{1,40}\Z")
LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}
CACHE_CASES = ("mysql", "redis", "caffeine")
DEFAULT_THREADS = (10, 50, 100, 200)
DEFAULT_LUA_THREADS = (50, 100, 200, 500)


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def require_run_id(value):
    if not RUN_ID.fullmatch(value):
        raise ValueError("run ID 仅允许字母、数字、下划线和连字符，长度 1-40")
    return value


def parse_local_base(value):
    parsed = urlparse(value)
    # COSEC: P0 fixture credentials and benchmark traffic are restricted to localhost.
    if (parsed.scheme != "http" or parsed.hostname not in LOCAL_HOSTS or parsed.username
            or parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("base URL 必须为显式端口的本地 HTTP 地址")
    if parsed.port is None or not 1 <= parsed.port <= 65535:
        raise ValueError("base URL 必须包含有效端口")
    return parsed.hostname, parsed.port


def json_write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def command(argv, *, env=None, check=True):
    completed = subprocess.run(argv, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, check=False)
    if check and completed.returncode != 0:
        raise RuntimeError("命令失败: " + " ".join(argv[:2]) + "\n" + completed.stderr[-2000:])
    return completed


def command_text(argv, *, env=None):
    completed = command(argv, env=env, check=False)
    return {"exitCode": completed.returncode, "stdout": completed.stdout, "stderr": completed.stderr}


def output_of(argv, *, env=None):
    completed = command(argv, env=env, check=False)
    return completed.stdout.strip() if completed.returncode == 0 else ""


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def workspace_fingerprint():
    head = output_of(["git", "rev-parse", "HEAD"])
    status = output_of(["git", "status", "--porcelain=v1"])
    diff = command(["git", "diff", "--no-ext-diff", "--binary"], check=False).stdout.encode("utf-8")
    untracked = output_of(["git", "ls-files", "--others", "--exclude-standard", "-z"]).split("\0")
    untracked_hashes = {}
    for relative in untracked:
        candidate = ROOT / relative
        if relative and candidate.is_file():
            untracked_hashes[relative] = sha256_bytes(candidate.read_bytes())
    return {
        "head": head,
        "worktreeDirty": bool(status),
        "status": status.splitlines(),
        "trackedDiffSha256": sha256_bytes(diff),
        "untrackedFileSha256": untracked_hashes,
    }


def parse_info(raw):
    values = {}
    for line in raw.splitlines():
        if ":" in line and not line.startswith("#"):
            key, value = line.split(":", 1)
            values[key] = value
    return values


def redis_snapshot():
    server = parse_info(output_of(["redis-cli", "INFO", "server"]))
    clients = parse_info(output_of(["redis-cli", "INFO", "clients"]))
    cpu = parse_info(output_of(["redis-cli", "INFO", "cpu"]))
    commands = parse_info(output_of(["redis-cli", "INFO", "commandstats"]))
    return {
        "server": {key: server.get(key) for key in ("redis_version", "redis_mode", "os", "uptime_in_seconds")},
        "clients": {key: clients.get(key) for key in ("connected_clients", "blocked_clients", "maxclients")},
        "cpu": {key: cpu.get(key) for key in ("used_cpu_sys", "used_cpu_user", "used_cpu_sys_children", "used_cpu_user_children")},
        "commandstats": commands,
    }


def redis_slowlog():
    # COSEC: slowlog arguments can contain access tokens, so persist only a
    # count and content fingerprint rather than command arguments.
    raw = output_of(["redis-cli", "SLOWLOG", "GET", "128"])
    return {"entryCount": output_of(["redis-cli", "SLOWLOG", "LEN"]),
            "contentSha256": sha256_bytes(raw.encode("utf-8"))}


def request_json(base, path):
    host, port = parse_local_base(base)
    connection = http.client.HTTPConnection(host, port, timeout=3)
    try:
        connection.request("GET", path, headers={"Accept": "application/json"})
        response = connection.getresponse()
        body = response.read()
    finally:
        connection.close()
    if response.status >= 400:
        raise RuntimeError(f"{path} 返回 HTTP {response.status}")
    return json.loads(body.decode("utf-8"))


def actuator_metric(base, name):
    try:
        return request_json(base, "/actuator/metrics/" + name)
    except Exception as error:
        return {"unavailable": str(error)}


def process_snapshot(pid):
    if not pid:
        return {}
    result = command(["ps", "-p", str(pid), "-o", "pid=,%cpu=,rss=,etime="], check=False)
    return {"raw": result.stdout.strip(), "exitCode": result.returncode}


def runtime_snapshot(base, pid):
    return {
        "at": utc_now(),
        "process": process_snapshot(pid),
        "redis": redis_snapshot(),
        "actuator": {
            "process.cpu.usage": actuator_metric(base, "process.cpu.usage"),
            "jvm.gc.pause": actuator_metric(base, "jvm.gc.pause"),
            "jvm.memory.used": actuator_metric(base, "jvm.memory.used"),
            "hikaricp.connections.active": actuator_metric(base, "hikaricp.connections.active"),
            "hikaricp.connections.pending": actuator_metric(base, "hikaricp.connections.pending"),
        },
    }


class Monitor:
    def __init__(self, base, pid, interval):
        self.base = base
        self.pid = pid
        self.interval = interval
        self.samples = []
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    def _run(self):
        while not self.stop_event.is_set():
            try:
                self.samples.append(runtime_snapshot(self.base, self.pid))
            except Exception as error:
                self.samples.append({"at": utc_now(), "monitorError": str(error)})
            self.stop_event.wait(self.interval)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop_event.set()
        self.thread.join(timeout=self.interval + 3)


def environment_snapshot(base, pid, mysql_host, mysql_port, mysql_user):
    hardware = {
        "cpuCores": output_of(["sysctl", "-n", "hw.ncpu"]),
        "memoryBytes": output_of(["sysctl", "-n", "hw.memsize"]),
        "os": output_of(["sw_vers"]),
        "machine": platform.machine(),
    }
    java = command_text(["java", "-version"])
    jmeter = command_text(["jmeter", "--version"])
    app_flags = command_text(["jcmd", str(pid), "VM.flags"]) if pid else {"unavailable": "未提供 app PID"}
    mysql_env = os.environ.copy()
    if os.environ.get("MYSQL_PASSWORD"):
        mysql_env["MYSQL_PWD"] = os.environ["MYSQL_PASSWORD"]
    mysql_version = command_text(["mysql", "--protocol=TCP", "-h", mysql_host, "-P", str(mysql_port),
                                  "-u", mysql_user, "-Nse", "SELECT VERSION()"], env=mysql_env)
    rabbit_version = command_text(["docker", "exec", "seckill-rabbitmq", "rabbitmqctl", "version"])
    return {
        "capturedAt": utc_now(),
        "source": workspace_fingerprint(),
        "hardware": hardware,
        "javaVersion": java,
        "jmeterVersion": jmeter,
        "application": {"pid": pid, "jvmFlags": app_flags, "instances": 1, "sameHostAsJmeter": True},
        "mysql": {"host": mysql_host, "port": mysql_port, "version": mysql_version},
        "redis": redis_snapshot(),
        "rabbitmq": {"version": rabbit_version},
        "redisClientConfig": {"lettuceMaxActive": 20, "lettuceMaxIdle": 10, "lettuceMinIdle": 5},
        "hikariConfig": {"maximumPoolSize": 20, "minimumIdle": 5, "connectionTimeoutMs": 3000},
    }


def run_jmeter(command_args, log_path, base, pid, monitor_path):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    before = runtime_snapshot(base, pid)
    with Monitor(base, pid, 1.0) as monitor, log_path.open("w", encoding="utf-8") as log:
        completed = subprocess.run(command_args, cwd=ROOT, env=jmeter_env(), text=True, stdout=log,
                                   stderr=subprocess.STDOUT, check=False)
    after = runtime_snapshot(base, pid)
    payload = {"before": before, "after": after, "samples": monitor.samples,
               "exitCode": completed.returncode, "redisSlowlogAfter": redis_slowlog()}
    json_write(monitor_path, payload)
    if completed.returncode != 0:
        raise RuntimeError(f"JMeter 执行失败，详情见 {log_path}")


def jmeter_env():
    env = os.environ.copy()
    env["JVM_ARGS"] = "-Xms512m -Xmx512m -XX:+UseG1GC"
    # Cleanup of a 50,000-order isolated fixture can exceed the smoke-test timeout.
    env["PERF_HTTP_TIMEOUT_SECONDS"] = "900"
    return env


def run_external(command_args, log_path):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        completed = subprocess.run(command_args, cwd=ROOT, env=jmeter_env(), text=True,
                                   stdout=log, stderr=subprocess.STDOUT, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"命令执行失败，详情见 {log_path}")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def require_success(summary, minimum_duration=60):
    if summary["duration_seconds"] < minimum_duration:
        raise RuntimeError(f"正式样本仅 {summary['duration_seconds']}s，未达到 {minimum_duration}s")
    if summary.get("http_or_api_error_count", summary.get("transport_or_api_error_requests", 0)) != 0:
        raise RuntimeError("正式样本存在 HTTP/API 错误，拒绝作为 P0 结果")
    if summary.get("business_rejected_requests", 0) != 0:
        raise RuntimeError("正式样本存在业务拒绝，拒绝作为 P0 结果")


def cache_run(args):
    started_source = workspace_fingerprint()
    root_id = require_run_id(args.run_id or "p0cache_" + datetime.now().strftime("%Y%m%d_%H%M%S"))
    all_samples = []
    for round_no in range(1, args.rounds + 1):
        run_id = require_run_id(f"{root_id}_r{round_no}")
        run_dir = CACHE_ROOT / run_id
        json_write(run_dir / "environment.json", environment_snapshot(args.base, args.app_pid, args.mysql_host,
                                                                        args.mysql_port, args.mysql_user))
        cache_tool = ROOT / "perf" / "cache_benchmark.py"
        logs = run_dir / "logs"
        run_external([sys.executable, str(cache_tool), "--base", args.base, "prepare", "--run-id", run_id],
                     logs / "prepare.log")
        try:
            for case in CACHE_CASES:
                for threads in DEFAULT_THREADS:
                    run_external([sys.executable, str(cache_tool), "--base", args.base, "setup-case",
                                  "--run-id", run_id, "--case", case], logs / f"{case}-{threads}-warm-setup.log")
                    warm_jtl = run_dir / f"{case}-{threads}-warm.jtl"
                    run_jmeter(["jmeter", "-n", "-t", str(ROOT / "perf" / "cache-test.jmx"), "-l", str(warm_jtl),
                                "-Jhost=127.0.0.1", "-Jport=" + str(parse_local_base(args.base)[1]),
                                "-Jstrategy=" + case.upper(), "-Jdata_file=" + str(run_dir / "requests.csv"),
                                "-Jthreads=" + str(threads), "-Jramp_seconds=5", "-Jduration_seconds=20",
                                "-Jsample_variables=cache_success,cache_message,coupon_id"],
                               logs / f"{case}-{threads}-warm.log", args.base, args.app_pid,
                               run_dir / f"{case}-{threads}-warm-monitor.json")
                    run_external([sys.executable, str(cache_tool), "--base", args.base, "setup-case",
                                  "--run-id", run_id, "--case", case], logs / f"{case}-{threads}-formal-setup.log")
                    formal_jtl = run_dir / f"{case}-{threads}-formal.jtl"
                    run_jmeter(["jmeter", "-n", "-t", str(ROOT / "perf" / "cache-test.jmx"), "-l", str(formal_jtl),
                                "-Jhost=127.0.0.1", "-Jport=" + str(parse_local_base(args.base)[1]),
                                "-Jstrategy=" + case.upper(), "-Jdata_file=" + str(run_dir / "requests.csv"),
                                "-Jthreads=" + str(threads), "-Jramp_seconds=5", "-Jduration_seconds=65",
                                "-Jsample_variables=cache_success,cache_message,coupon_id"],
                               logs / f"{case}-{threads}-formal.log", args.base, args.app_pid,
                               run_dir / f"{case}-{threads}-formal-monitor.json")
                    run_external([sys.executable, str(cache_tool), "--base", args.base, "collect", "--run-id", run_id,
                                  "--case", case, "--threads", str(threads), "--jtl", str(formal_jtl)],
                                 logs / f"{case}-{threads}-collect.log")
                    summary_path = run_dir / f"{case}-{threads}-summary.json"
                    summary = read_json(summary_path)
                    require_success(summary)
                    summary["observability"] = {
                        "formalMonitor": str(run_dir / f"{case}-{threads}-formal-monitor.json"),
                        "warmMonitor": str(run_dir / f"{case}-{threads}-warm-monitor.json"),
                    }
                    json_write(summary_path, summary)
                    all_samples.append(summary)
            run_external([sys.executable, str(cache_tool), "--base", args.base, "report", "--run-id", run_id],
                         logs / "report.log")
        finally:
            run_external([sys.executable, str(cache_tool), "--base", args.base, "cleanup", "--run-id", run_id],
                         logs / "cleanup.log")
    finish_source = workspace_fingerprint()
    if finish_source != started_source:
        raise RuntimeError("测试期间工作区指纹变化，拒绝汇总跨版本样本")
    aggregate_cache(root_id, all_samples, started_source)


def aggregate_cache(root_id, samples, source):
    groups = {}
    for sample in samples:
        groups.setdefault((sample["case"], sample["threads"]), []).append(sample)
    aggregate = {"run_id": root_id, "source": source, "samples": [], "generated_at": utc_now()}
    lines = ["| 模式 | 并发 | 三轮 QPS | 采用 QPS(中位数) | 三轮 P99(ms) | 采用 P99(ms, 中位数) |", "| -- | -: | -- | --: | -- | --: |"]
    labels = {"mysql": "MYSQL", "redis": "REDIS", "caffeine": "CAFFEINE"}
    for (case, threads), values in sorted(groups.items()):
        qps = [item["qps"] for item in values]
        p99 = [item["p99_ms"] for item in values]
        entry = {"mode": case, "threads": threads, "rounds": [{"run_id": item["run_id"], "qps": item["qps"],
                 "p50_ms": item["p50_ms"], "p95_ms": item["p95_ms"], "p99_ms": item["p99_ms"],
                 "average_ms": item["average_ms"], "max_ms": item["max_ms"],
                 "error_rate": item["http_or_api_error_rate"], "cache_metrics": item["cache_metrics"]} for item in values],
                 "qps_median": statistics.median(qps), "p99_median_ms": statistics.median(p99),
                 "qps_range": [min(qps), max(qps)], "p99_range_ms": [min(p99), max(p99)]}
        aggregate["samples"].append(entry)
        lines.append(f"| {labels[case]} | {threads} | {', '.join(f'{value:.2f}' for value in qps)} | "
                     f"{entry['qps_median']:.2f} | {', '.join(f'{value:.2f}' for value in p99)} | {entry['p99_median_ms']:.2f} |")
    destination = CACHE_ROOT / root_id
    json_write(destination / "aggregate.json", aggregate)
    (destination / "aggregate.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def lua_run(args):
    started_source = workspace_fingerprint()
    root_id = require_run_id(args.run_id or "p0lua_" + datetime.now().strftime("%Y%m%d_%H%M%S"))
    json_write(LUA_ROOT / root_id / "environment.json", environment_snapshot(args.base, args.app_pid, args.mysql_host,
                                                                                args.mysql_port, args.mysql_user))
    samples = []
    for round_no in range(1, args.rounds + 1):
        for threads in DEFAULT_LUA_THREADS:
            run_id = require_run_id(f"{root_id}_r{round_no}_t{threads}")
            result_dir = LUA_ROOT / run_id
            logs = result_dir / "logs"
            jtl = result_dir / "acceptance.jtl"
            run_external([sys.executable, str(ROOT / "performance-test" / "scripts" / "fixtures.py"), "--base", args.base,
                          "prepare", "--run-id", run_id, "--users", "5000", "--shops", "1", "--coupons", "10",
                          "--stock", "10000", "--coupons-per-user", "10"], logs / "prepare.log")
            try:
                run_jmeter(["jmeter", "-n", "-t", str(ROOT / "performance-test" / "jmeter" / "seckill-smoke.jmx"),
                            "-l", str(jtl), "-JbaseUrl=" + args.base,
                            "-JdataFile=" + str(ROOT / "performance-test" / "data" / run_id / "seckill-requests.csv"),
                            "-Jthreads=" + str(threads), "-JrampUp=5", "-Jduration=65",
                            "-Jsample_variables=api_success,business_success,business_message,order_no,username,coupon_id"],
                           logs / "formal.log", args.base, args.app_pid, result_dir / "formal-monitor.json")
                run_external([sys.executable, str(ROOT / "performance-test" / "scripts" / "fixtures.py"), "--base", args.base,
                              "summarize", "--run-id", run_id, "--jtl", str(jtl)], logs / "summarize.log")
                summary = read_json(result_dir / "summary.json")
                require_success(summary)
                run_external([sys.executable, str(ROOT / "performance-test" / "scripts" / "fixtures.py"), "--base", args.base,
                              "verify", "--run-id", run_id, "--jtl", str(jtl), "--timeout-seconds", "600"], logs / "verify.log")
                verification = read_json(result_dir / "verification.json")
                if not all(verification[key] for key in ("stock_consistent", "no_negative_stock", "no_duplicate_claim", "pending_drained")):
                    raise RuntimeError("Lua 一致性校验未全部通过")
                samples.append({"run_id": run_id, "threads": threads, "summary": summary, "verification": verification,
                                "monitor": str(result_dir / "formal-monitor.json")})
            finally:
                run_external([sys.executable, str(ROOT / "performance-test" / "scripts" / "fixtures.py"), "--base", args.base,
                              "cleanup", "--run-id", run_id], logs / "cleanup.log")
    finish_source = workspace_fingerprint()
    if finish_source != started_source:
        raise RuntimeError("测试期间工作区指纹变化，拒绝汇总跨版本样本")
    aggregate_lua(root_id, samples, started_source)


def aggregate_lua(root_id, samples, source):
    groups = {}
    for sample in samples:
        groups.setdefault(sample["threads"], []).append(sample)
    aggregate = {"run_id": root_id, "source": source, "samples": [], "generated_at": utc_now(),
                 "load": {"users": 5000, "coupons": 10, "couponsPerUser": 10, "stockPerCoupon": 10000,
                          "rampUpSeconds": 5, "durationSeconds": 65}}
    lines = ["| 并发 | 三轮 QPS | 采用 QPS(中位数) | 三轮 P99(ms) | 采用 P99(ms, 中位数) | 错误率 | 一致性 |",
             "| --: | -- | --: | -- | --: | --: | -- |"]
    for threads, values in sorted(groups.items()):
        qps = [item["summary"]["throughput_qps"] for item in values]
        p99 = [item["summary"]["p99_ms"] for item in values]
        entry = {"threads": threads, "rounds": values, "qps_median": statistics.median(qps),
                 "p99_median_ms": statistics.median(p99), "qps_range": [min(qps), max(qps)],
                 "p99_range_ms": [min(p99), max(p99)]}
        aggregate["samples"].append(entry)
        consistent = all(item["verification"]["stock_consistent"] and item["verification"]["no_negative_stock"]
                         and item["verification"]["no_duplicate_claim"] and item["verification"]["pending_drained"]
                         for item in values)
        errors = max(item["summary"]["error_rate"] for item in values)
        lines.append(f"| {threads} | {', '.join(f'{value:.2f}' for value in qps)} | {entry['qps_median']:.2f} | "
                     f"{', '.join(f'{value:.2f}' for value in p99)} | {entry['p99_median_ms']:.2f} | "
                     f"{errors * 100:.4f}% | {'通过' if consistent else '失败'} |")
    destination = LUA_ROOT / root_id
    json_write(destination / "aggregate.json", aggregate)
    (destination / "aggregate.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("cache", "lua"))
    parser.add_argument("--run-id")
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--app-pid", type=int, required=True)
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=13306)
    parser.add_argument("--mysql-user", default="root")
    args = parser.parse_args()
    parse_local_base(args.base)
    if not 1 <= args.rounds <= 3:
        parser.error("rounds 必须为 1-3")
    if args.app_pid <= 0 or not 1 <= args.mysql_port <= 65535:
        parser.error("app-pid 或 mysql-port 无效")
    if args.mode == "cache":
        cache_run(args)
    else:
        lua_run(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, http.client.HTTPException, json.JSONDecodeError) as error:
        print("error: " + str(error), file=sys.stderr)
        raise SystemExit(1)
