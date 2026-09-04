# P0 Performance Retest Final Report

## Scope

This document records only the P0 retests completed on 2026-08-28:

- Activity hot-query cache comparison: MYSQL, REDIS, and CAFFEINE + Redis.
- Redis Lua coupon-claim stability test.
- RabbitMQ retains the prior correctness acceptance result only. No new RabbitMQ throughput or latency claim is made here.

All adopted values are the median of three formal runs, not a one-second peak or the best run.

## Reproducibility Record

| Item | Value |
| -- | -- |
| Git HEAD | `488e4121abc58694e1d1419385f26045443dfa6f` |
| Source state | Dirty worktree; each matrix run recorded a source fingerprint and checked that it did not change during that matrix. |
| Test time | 2026-08-28 |
| Host | macOS 15.5, arm64, 12 CPU cores, 16 GiB memory |
| Application | One local Spring Boot instance |
| Application JDK | Microsoft OpenJDK 17.0.19 LTS |
| Application JVM | `-Xms512m -Xmx512m -XX:+UseG1GC -XX:TieredStopAtLevel=1` |
| JMeter | Apache JMeter 5.6.3, on the same host as the application |
| MySQL | Local MySQL 8.0.46, port 13306 |
| Redis | Local standalone Redis 8.10.1, port 6379 |
| RabbitMQ | Local RabbitMQ 3.12.14 |

The full Lua environment and source fingerprint are in [environment.json](p0l_20260828b/environment.json). Cache run environment snapshots are stored under `target/perf-cache/p0c_20260828b_r{1,2,3}/environment.json`.

## Cache Benchmark

The cache matrix used the same dataset, endpoint, JVM, service instance, JMeter host, concurrency and duration for all three modes. Each case had a warm-up run followed by a 65-second formal run. The complete 3 modes x 4 concurrency levels matrix was repeated three times without changing the environment. Formal JTL, warm-up JTL, summary and monitor JSON files are retained per run.

All 36 formal cache runs had a `0` error rate. At 100 concurrency, Caffeine hit rate ranged from 99.9901% to 99.9908% across the three formal runs.

| Mode | Concurrency | Three QPS | Adopted QPS | Three P99 | Adopted P99 |
| -- | -: | -- | --: | -- | --: |
| MYSQL | 10 | 3308.11, 3308.00, 3274.89 | 3308.00 | 4, 4, 4 ms | 4 ms |
| MYSQL | 50 | 4295.03, 4281.56, 4285.68 | 4285.68 | 19, 20, 19 ms | 19 ms |
| MYSQL | 100 | 4364.94, 4302.21, 4314.30 | 4314.30 | 43, 44, 44 ms | 44 ms |
| MYSQL | 200 | 4328.67, 4374.78, 4315.26 | 4328.67 | 95, 94, 94 ms | 94 ms |
| REDIS | 10 | 9694.23, 9723.84, 9693.85 | 9694.23 | 2, 2, 2 ms | 2 ms |
| REDIS | 50 | 10536.92, 10574.75, 10553.64 | 10553.64 | 6, 6, 6 ms | 6 ms |
| REDIS | 100 | 10531.94, 10550.93, 10538.42 | 10538.42 | 12, 12, 12 ms | 12 ms |
| REDIS | 200 | 10519.45, 10502.98, 10497.41 | 10502.98 | 25, 24, 25 ms | 25 ms |
| CAFFEINE | 10 | 11748.30, 11851.59, 11819.74 | 11819.74 | 1, 1, 1 ms | 1 ms |
| CAFFEINE | 50 | 12800.03, 12813.18, 12812.53 | 12812.53 | 5, 5, 5 ms | 5 ms |
| CAFFEINE | 100 | 12768.84, 12735.18, 12754.77 | 12754.77 | 10, 11, 10 ms | 10 ms |
| CAFFEINE | 200 | 12741.28, 12727.18, 12739.79 | 12739.79 | 21, 20, 20 ms | 20 ms |

At 100 concurrency, adopted median values show:

- Redis QPS was about 2.44x the MySQL direct-query baseline, and P99 decreased from 44 ms to 12 ms (about 72.7%).
- Caffeine + Redis QPS was about 21.0% above Redis-only, and P99 decreased from 12 ms to 10 ms (about 16.7%).

Evidence:

- Aggregate Markdown: [target/perf-cache/p0c_20260828b/aggregate.md](../../target/perf-cache/p0c_20260828b/aggregate.md)
- Aggregate JSON: [target/perf-cache/p0c_20260828b/aggregate.json](../../target/perf-cache/p0c_20260828b/aggregate.json)
- Per-round evidence: `target/perf-cache/p0c_20260828b_r{1,2,3}/`, including `*-warm.jtl`, `*-formal.jtl`, `*-summary.json`, and `*-monitor.json`.

## Redis Lua Stability Benchmark

Every formal run used a 5-second ramp-up and approximately 65 seconds of effective sampling. Each request used a distinct user-coupon pair. Each run provisioned 5,000 users, 10 coupons per user and 10,000 stock per coupon, avoiding stock-exhausted and duplicate-claim fast-failure paths.

For every Lua run, the JMeter result and the consistency verification use the same `run_id`. All 12 formal runs had zero transport/API errors and zero business rejections. In every run: accepted request count equals order count, stock reconciliation passed, no negative stock occurred, no duplicate claim pair occurred, and pending records were drained.

| Concurrency | Three QPS | Adopted QPS | Three P99 | Adopted P99 | Error Rate | Consistency |
| --: | -- | --: | -- | --: | --: | -- |
| 50 | 699.10, 690.41, 679.73 | 690.41 | 108, 107, 118 ms | 108 ms | 0.0000% | All three runs passed |
| 100 | 696.99, 676.37, 672.20 | 676.37 | 209, 225, 256 ms | 225 ms | 0.0000% | All three runs passed |
| 200 | 674.53, 632.05, 686.59 | 674.53 | 458, 463, 374 ms | 458 ms | 0.0000% | All three runs passed |
| 500 | 671.51, 662.27, 682.23 | 671.51 | 1041, 1051, 968 ms | 1041 ms | 0.0000% | All three runs passed |

The adopted maximum stable operating point is 50 concurrency: about 690 QPS with a 108 ms P99. Increasing concurrency did not improve throughput but materially worsened P99, so 1000 concurrency was intentionally not tested and no peak-QPS claim is made.

Evidence:

- Aggregate Markdown: [p0l_20260828b/aggregate.md](p0l_20260828b/aggregate.md)
- Aggregate JSON: [p0l_20260828b/aggregate.json](p0l_20260828b/aggregate.json)
- Per-run evidence: `p0l_20260828b_r{1,2,3}_t{50,100,200,500}/`, each containing `acceptance.jtl`, `summary.json`, `verification.json`, and `formal-monitor.json`.

## RabbitMQ Correctness Status

No new RabbitMQ performance benchmark was run for this P0 retest. The retained correctness acceptance result is: 5,000 independent users produced 5,000 valid orders after the pending/publisher-confirm race fix; no duplicate business order remained, and pending and DLQ were both drained. This is a correctness result, not a QPS or latency comparison.

## Key Findings

- Built a Caffeine + Redis two-level cache for hot activity reads. In a local single-instance benchmark at 100 concurrency with three 65-second runs, the adopted median improved throughput from about 4.3k QPS for MySQL direct reads to about 12.8k QPS, while P99 fell from 44 ms to 10 ms; Caffeine hit rate was about 99.99%.
- Consolidated activity validation, stock decrement, duplicate-claim checks and pending creation into one Redis Lua execution. In a local single-instance benchmark at 50 concurrency with three 65-second runs, the adopted stable result was about 690 QPS with 108 ms P99, zero system errors, no oversell or duplicate claims, and no pending residue.
- Coordinated first publish and compensation through publisher confirm and a pending state transition, and made the consumer idempotent for at-least-once delivery. In the 5,000-independent-user correctness acceptance, 5,000 valid orders were persisted with no duplicate business order and no remaining pending or DLQ message.

These are local single-instance baseline results. They must not be described as production capacity or as a distributed deployment throughput claim.
