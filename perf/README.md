# Coupon Detail Cache Performance Test

This directory contains the strict, local-only coupon-detail cache comparison. All cases use the same authenticated HTTP envelope, 100 active coupon fixtures, 80/20 hot-cold request distribution, one JVM, and one JMeter plan. Only the detail-read source changes.

## Preconditions

- Start one application instance with `spring.profiles.active=perf`.
- Start MySQL, Redis, and RabbitMQ using the normal local development configuration.
- Export the local merchant password. The scripts do not store it or write access tokens outside `target/perf-cache/`.

```sh
export PERF_MERCHANT_PASSWORD='local-merchant-password'
```

The helper accepts only a localhost HTTP endpoint. It creates exactly 100 coupons named `perf_cache_<run-id>_<n>` and cleanup is limited to that exact prefix.

## Prepare

```sh
python3 perf/cache_benchmark.py --base http://127.0.0.1:18080 prepare --run-id cache_20260827
```

This writes the JMeter dataset and a non-secret manifest to `target/perf-cache/cache_20260827/`. The request list uses 80% traffic across ten hot coupons and 20% across the remaining ninety coupons. `setup-case` refreshes the CSV's JWT immediately before each JMeter sample while retaining the exact coupon IDs and request mix, so a resumed matrix cannot measure an expired login response.

## Cases

| Case | Same detail payload source | Expected cache-path work per request |
| --- | --- | --- |
| `mysql` | `CouponRepository.findById` | one MySQL query |
| `redis` | published active-version pointer plus immutable Redis snapshot | one pointer read plus one snapshot read |
| `caffeine` | production `CouponCacheService.getCouponDetail` | Caffeine L1 plus the production Redis version-pointer consistency check; an L1 hit does not fetch the snapshot |

The `caffeine` case retains the production logical-expiry and asynchronous-refresh behavior. The cache metrics report only coupon-detail-path work; authentication's Redis session reads are deliberately excluded.

## Full Matrix

With the application started under the `perf` profile, run the complete 3 x 4 matrix. It performs a 20-second validation/warm-up before every sample, republishes the same immutable snapshot to renew only its cache TTL, restores the intended cache state, resets counters, then collects a 65-second formal sample at 10, 50, 100, and 200 threads. The fixed 5-second Ramp-Up leaves at least 60 seconds at target concurrency; reported QPS conservatively includes the ramp interval.

```sh
export PERF_MERCHANT_PASSWORD='local-merchant-password'
./perf/run-cache-matrix.sh cache_20260827
```

`JMETER_JVM_ARGS` defaults to `-Xms512m -Xmx512m -XX:+UseG1GC` for every JMeter invocation. Start the application with fixed JVM options too, for example `JAVA_TOOL_OPTIONS='-Xms512m -Xmx512m -XX:+UseG1GC'`. Before every JMeter run, the runner clears that sample's JTL because the JMeter CLI appends to an existing file. The script writes JTL files, per-sample JSON, and `report.md` under `target/perf-cache/<run-id>/`; tokens are kept only in the ignored request CSV.

If a terminal/session time limit requires splitting a long matrix, reuse the same prepared run and select only the remaining fixed cases/threads. Each selected sample still republishes its identical snapshot, prewarms, and resets metrics before collection:

```sh
PERF_REUSE_RUN=1 PERF_SKIP_REPORT=1 PERF_CACHE_CASES=redis \
  PERF_CACHE_THREADS='100 200' ./perf/run-cache-matrix.sh cache_20260827
```

For a constrained local terminal, `PERF_SKIP_VALIDATION=1` skips only the extra JMeter validation run. It does not skip the API-level snapshot publication, full fixture prewarm, or metric reset that occurs immediately before the formal sample.

## Run One Case

Prepare the intended cache state, run a short 15-second validation first, then run the formal 60-second sample. Repeat for `mysql`, `redis`, and `caffeine`.

```sh
python3 perf/cache_benchmark.py --base http://127.0.0.1:18080 setup-case --run-id cache_20260827 --case caffeine

jmeter -n -t perf/cache-test.jmx \
  -l target/perf-cache/cache_20260827/caffeine-validate.jtl \
  -Jhost=127.0.0.1 -Jport=18080 \
  -Jdata_file=target/perf-cache/cache_20260827/requests.csv \
  -Jthreads=20 -Jramp_seconds=2 -Jduration_seconds=15 \
  -Jsample_variables=cache_success,cache_message,coupon_id

python3 perf/cache_benchmark.py --base http://127.0.0.1:18080 setup-case --run-id cache_20260827 --case caffeine

jmeter -n -t perf/cache-test.jmx \
  -l target/perf-cache/cache_20260827/caffeine-50.jtl \
  -Jhost=127.0.0.1 -Jport=18080 \
  -Jdata_file=target/perf-cache/cache_20260827/requests.csv \
  -Jthreads=50 -Jramp_seconds=5 -Jduration_seconds=60 \
  -Jsample_variables=cache_success,cache_message,coupon_id

python3 perf/cache_benchmark.py --base http://127.0.0.1:18080 collect --run-id cache_20260827 --case caffeine --threads 50 \
  --jtl target/perf-cache/cache_20260827/caffeine-50.jtl
```

Run each case at 10, 50, 100, and 200 threads. `collect` writes a sanitized summary JSON without tokens.

`mysql` bypasses both caches for every read. `redis` uses the already-published Redis version pointer and snapshot while leaving Caffeine empty. `caffeine` prewarms the production Caffeine path.

In all cases, `redis_version_pointer_read` is expected to remain close to the observed detail request count. A Caffeine snapshot hit in this application still checks the Redis active-version pointer, so this is not a pure in-JVM-cache benchmark.

## Cleanup

```sh
python3 perf/cache_benchmark.py --base http://127.0.0.1:18080 cleanup --run-id cache_20260827
```
