#!/usr/bin/env python3
"""
秒杀系统压测脚本
用法: python3 bench.py <并发数> <总请求数> <couponId>
示例: python3 bench.py 100 10000 3
"""
import sys
import time
import json
import threading
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = "http://localhost:8080"
COUPON_ID = int(sys.argv[3]) if len(sys.argv) > 3 else 3
CONCURRENT = int(sys.argv[1]) if len(sys.argv) > 1 else 100
TOTAL = int(sys.argv[2]) if len(sys.argv) > 2 else 10000

# 预先登录获取 token（每个线程独立用户）
TOKENS = []
TOKEN_LOCK = threading.Lock()
USER_IDX = [0]

stats = {"success": 0, "fail": 0, "times": [], "errors": {}}
stats_lock = threading.Lock()

def register_and_login(idx):
    """注册+登录，返回 token"""
    uid = f"bench_{int(time.time()*1000)}_{idx}"

    # 注册
    data = json.dumps({"username": uid, "password": "123"}).encode()
    req = urllib.request.Request(f"{BASE}/api/auth/register",
        data=data, headers={"Content-Type": "application/json"}, method="POST")
    try:
        urllib.request.urlopen(req, timeout=5).read()
    except:
        pass

    # 登录
    req = urllib.request.Request(f"{BASE}/api/auth/login",
        data=data, headers={"Content-Type": "application/json"}, method="POST")
    resp = json.loads(urllib.request.urlopen(req, timeout=5).read())
    return resp.get("accessToken", "")

def do_seckill(token, idx):
    """执行一次秒杀"""
    start = time.time()
    try:
        data = json.dumps({
            "couponId": COUPON_ID,
            "deviceFingerprint": f"bench-device-{idx}"
        }).encode()
        req = urllib.request.Request(f"{BASE}/api/seckill/execute",
            data=data,
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {token}"},
            method="POST")
        resp = json.loads(urllib.request.urlopen(req, timeout=10).read())
        elapsed = (time.time() - start) * 1000

        with stats_lock:
            if resp.get("success"):
                stats["success"] += 1
            else:
                stats["fail"] += 1
                msg = resp.get("message", "unknown")
                stats["errors"][msg] = stats["errors"].get(msg, 0) + 1
            stats["times"].append(elapsed)
        return resp.get("success", False)
    except Exception as e:
        elapsed = (time.time() - start) * 1000
        with stats_lock:
            stats["fail"] += 1
            stats["errors"][str(e)[:50]] = stats["errors"].get(str(e)[:50], 0) + 1
            stats["times"].append(elapsed)
        return False

def worker(idx):
    """每个线程: 注册→登录→循环抢券"""
    token = register_and_login(idx)
    if not token:
        return

    # 每个用户抢到的券数有限（一人一单），抢到就停
    bought = 0
    max_attempts = max(1, TOTAL // CONCURRENT)
    for _ in range(max_attempts):
        if do_seckill(token, idx):
            bought += 1
            if bought >= 1:  # 一人一单，抢到即停
                break

print(f"🔥 秒杀压测开始")
print(f"   并发: {CONCURRENT} | 目标: {TOTAL}请求 | 优惠券ID: {COUPON_ID}")
print(f"   接口: POST {BASE}/api/seckill/execute")
print()

t0 = time.time()

with ThreadPoolExecutor(max_workers=CONCURRENT) as pool:
    futures = [pool.submit(worker, i) for i in range(CONCURRENT)]
    for f in as_completed(futures):
        try:
            f.result()
        except:
            pass

elapsed = time.time() - t0
total_req = len(stats["times"])
success = stats["success"]
fail = stats["fail"]
qps = total_req / elapsed if elapsed > 0 else 0

times = sorted(stats["times"])
avg = sum(times) / len(times) if times else 0
p50 = times[len(times)//2] if times else 0
p99 = times[int(len(times)*0.99)] if len(times) > 1 else 0
max_rt = times[-1] if times else 0

print("=" * 55)
print(f"📊 压测结果")
print("=" * 55)
print(f"  总请求数:    {total_req}")
print(f"  成功:        {success} ({success*100/total_req:.1f}%)" if total_req else "")
print(f"  失败:        {fail} ({fail*100/total_req:.1f}%)" if total_req else "")
print(f"  耗时:        {elapsed:.2f}s")
print(f"  QPS:         {qps:.0f} req/s")
print(f"  RT 平均:     {avg:.1f}ms")
print(f"  RT P50:      {p50:.1f}ms")
print(f"  RT P99:      {p99:.1f}ms")
print(f"  RT 最大:     {max_rt:.1f}ms")
print(f"  并发线程:    {CONCURRENT}")
if stats["errors"]:
    print(f"\n  错误分布:")
    for msg, cnt in sorted(stats["errors"].items(), key=lambda x:-x[1])[:5]:
        print(f"    [{cnt}] {msg}")

# 查库存
try:
    import subprocess
    stock = subprocess.check_output(["redis-cli", "HGET", f"seckill:coupon:{COUPON_ID}", "remain"]).decode().strip()
    print(f"\n  Redis库存:   {stock}")
except:
    pass
print("=" * 55)