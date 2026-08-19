#!/usr/bin/env python3
"""纯秒杀压测：预加载 token，只测 POST /api/seckill/execute"""
import sys, time, json, threading, urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8080"
COUPON_ID = int(sys.argv[1]) if len(sys.argv) > 1 else 3
CONCURRENT = int(sys.argv[2]) if len(sys.argv) > 2 else 200
REQUESTS = int(sys.argv[3]) if len(sys.argv) > 3 else 500

# 加载 token
with open("/tmp/seckill_tokens.txt") as f:
    TOKENS = [t.strip() for t in f if t.strip()]
print(f"Tokens loaded: {len(TOKENS)}")

stats = {"success": 0, "fail": 0, "times": [], "errors": {}, "order_nos": []}
lock = threading.Lock()

def seckill(token, idx):
    start = time.time()
    try:
        data = json.dumps({
            "couponId": COUPON_ID,
            "deviceFingerprint": f"p-{idx}"
        }).encode()
        req = urllib.request.Request(f"{BASE}/api/seckill/execute",
            data=data,
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {token}"},
            method="POST")
        resp = json.loads(urllib.request.urlopen(req, timeout=10).read())
        elapsed = (time.time() - start) * 1000
        with lock:
            if resp.get("success"):
                stats["success"] += 1
                if resp.get("orderNo"):
                    stats["order_nos"].append(resp["orderNo"])
            else:
                stats["fail"] += 1
                stats["errors"][resp.get("message","?")] = stats["errors"].get(resp.get("message","?"), 0) + 1
            stats["times"].append(elapsed)
    except Exception as e:
        elapsed = (time.time() - start) * 1000
        with lock:
            stats["fail"] += 1
            stats["errors"][str(e)[:60]] = stats["errors"].get(str(e)[:60], 0) + 1
            stats["times"].append(elapsed)

# 重置
import subprocess
subprocess.run(["redis-cli", "HSET", f"seckill:coupon:{COUPON_ID}", "remain", "5000", "version", "0"], capture_output=True)
subprocess.run(["redis-cli", "DEL", f"seckill:user:{COUPON_ID}"], capture_output=True)

print(f"🔥 纯秒杀压测: {CONCURRENT}并发 × {REQUESTS}请求")
t0 = time.time()

with ThreadPoolExecutor(max_workers=CONCURRENT) as pool:
    futures = []
    for i in range(REQUESTS):
        token = TOKENS[i % len(TOKENS)]
        futures.append(pool.submit(seckill, token, i))
    for f in futures:
        try: f.result()
        except: pass

elapsed = time.time() - t0
total = len(stats["times"])
times = sorted(stats["times"])
avg = sum(times)/len(times) if times else 0
p50 = times[len(times)//2] if times else 0
p99 = times[int(len(times)*0.99)] if len(times)>1 else 0

print("=" * 55)
print(f"  总请求: {total} | 成功: {stats['success']} | 失败: {stats['fail']}")
print(f"  耗时: {elapsed:.2f}s | QPS: {total/elapsed:.0f} req/s")
print(f"  RT avg: {avg:.1f}ms | P50: {p50:.1f}ms | P99: {p99:.1f}ms | max: {times[-1]:.0f}ms" if times else "")
if stats["errors"]:
    print(f"\n  错误:")
    for k,v in sorted(stats["errors"].items(), key=lambda x:-x[1])[:5]:
        print(f"    [{v}] {k}")
stock = subprocess.run(["redis-cli", "HGET", f"seckill:coupon:{COUPON_ID}", "remain"], capture_output=True).stdout.decode().strip()
print(f"  库存: {stock}")
duplicate_orders = len(stats["order_nos"]) - len(set(stats["order_nos"]))
print(f"  重复订单号: {duplicate_orders} | 结论: {'PASS' if duplicate_orders == 0 else 'FAIL'}")
print("=" * 55)
