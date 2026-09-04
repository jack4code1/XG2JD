# 性能测试记录

更新时间：2026-08-28
适用范围：本地单实例基准压测，不代表生产集群容量。

## 1. 结论与使用边界

本仓库只保留 2026-08-28 P0 补测作为当前可信性能结论。采用值均为三轮正式采样的中位数，不使用瞬时峰值或最好一轮。

- 缓存对比采用同一接口、数据集、JVM、服务实例和 JMeter 主机，在 10、50、100、200 并发下完成 3 x 4 矩阵并重复三轮。
- Redis Lua 测试使用足够库存和唯一的用户-券组合，避免库存不足或一人一单拒绝造成虚高 QPS；每档约 65 秒正式采样，三轮重复。
- RabbitMQ 当前仅保留可靠投递与幂等正确性验收；没有同步下单对照，因此不能写“MQ 使 QPS 提升 X 倍”或“P99 下降 X%”。

## 2. 固定环境

| 项目 | 取值 |
| -- | -- |
| Git HEAD | `488e4121abc58694e1d1419385f26045443dfa6f` |
| 主机 | macOS 15.5，arm64，12 CPU 核，16 GiB 内存 |
| 服务 | 单个本地 Spring Boot 实例 |
| JDK | Microsoft OpenJDK 17.0.19 LTS |
| JVM | `-Xms512m -Xmx512m -XX:+UseG1GC -XX:TieredStopAtLevel=1` |
| JMeter | 5.6.3，与服务端同机 |
| MySQL | 本地 8.0.46 |
| Redis | 本地 standalone 8.10.1 |
| RabbitMQ | 本地 3.12.14 |

## 3. 缓存测试最终数据

正式采样时长为 65 秒，所有 36 个正式样本错误率均为 0。以下为每档三轮的中位数。

| 模式 | 并发 | 采用 QPS | 采用 P99 |
| -- | -: | --: | --: |
| MYSQL | 10 | 3308.00 | 4 ms |
| MYSQL | 50 | 4285.68 | 19 ms |
| MYSQL | 100 | 4314.30 | 44 ms |
| MYSQL | 200 | 4328.67 | 94 ms |
| REDIS | 10 | 9694.23 | 2 ms |
| REDIS | 50 | 10553.64 | 6 ms |
| REDIS | 100 | 10538.42 | 12 ms |
| REDIS | 200 | 10502.98 | 25 ms |
| CAFFEINE + REDIS | 10 | 11819.74 | 1 ms |
| CAFFEINE + REDIS | 50 | 12812.53 | 5 ms |
| CAFFEINE + REDIS | 100 | 12754.77 | 10 ms |
| CAFFEINE + REDIS | 200 | 12739.79 | 20 ms |

在 100 并发下：Redis 相对 MySQL 的 QPS 约为 2.44 倍，P99 从 44 ms 降至 12 ms；Caffeine + Redis 相对 Redis 的 QPS 增加约 21.0%，P99 从 12 ms 降至 10 ms。Caffeine 命中率约为 99.99%。

## 4. Redis Lua 稳定性最终数据

每档使用 5 秒 Ramp-Up、约 65 秒正式采样，测试结果与库存、领取集合、订单和 pending 审计使用同一 `run_id` 对应。

| 并发 | 三轮 QPS | 采用 QPS | 三轮 P99 | 采用 P99 | 系统错误率 | 一致性 |
| --: | -- | --: | -- | --: | --: | -- |
| 50 | 699.10, 690.41, 679.73 | 690.41 | 108, 107, 118 ms | 108 ms | 0.0000% | 三轮通过 |
| 100 | 696.99, 676.37, 672.20 | 676.37 | 209, 225, 256 ms | 225 ms | 0.0000% | 三轮通过 |
| 200 | 674.53, 632.05, 686.59 | 674.53 | 458, 463, 374 ms | 458 ms | 0.0000% | 三轮通过 |
| 500 | 671.51, 662.27, 682.23 | 671.51 | 1041, 1051, 968 ms | 1041 ms | 0.0000% | 三轮通过 |

50 并发是采用的最大稳定档位：约 690 QPS、P99 108 ms。并发继续上升时吞吐未增加而尾延迟显著恶化，因此没有测试 1000 并发，也不报告峰值 QPS。12 轮均满足：成功受理数等于订单数、无负库存、无重复领取、pending 清零。

## 5. RabbitMQ 正确性状态

保留的验收结论：在修复 pending 与 publisher confirm 竞态后，5,000 个独立用户最终生成 5,000 个有效订单，无重复业务订单，pending 与 DLQ 均清零。

当前设计事实：Redis Lua 原子受理、RabbitMQ 异步创建订单、publisher confirm、pending 重投补偿、消费者幂等和 DLQ 都已实现。此处不附加新的 RabbitMQ QPS、P99 或同步对照结论。

## 6. 复现入口

启动本地 MySQL、Redis、RabbitMQ 及一个 `perf` profile 服务实例后，按 `performance-test/README.md` 和 `perf/README.md` 执行。

```sh
export PERF_MERCHANT_PASSWORD='<local merchant password>'
python3 performance-test/scripts/run-p0-stability.py cache --run-id p0c_<date> --app-pid <pid>
python3 performance-test/scripts/run-p0-stability.py lua --run-id p0l_<date> --app-pid <pid>
```

运行器会保存正式/预热 JTL、摘要、环境快照、Redis commandstats、GC、连接池与一致性审计。JTL 可能包含短期访问令牌，始终保留在 Git 忽略目录，禁止提交。

## 7. 本机证据位置与版本库策略

以下本机证据目录没有推送到 GitHub：

- `target/perf-cache/p0c_20260828b/` 与 `target/perf-cache/p0c_20260828b_r{1,2,3}/`
- `performance-test/results/p0l_20260828b/` 与 `performance-test/results/p0l_20260828b_r*_t*/`
- `performance-test/results/rabbit_fix_burst_20260828/`

它们包含大体积 JTL 或可能含短期令牌。最终的脱敏结论保存在本文件和 `performance-test/results/p0-20260828b-final-report.md`。历史短时、修复前或不满足当前口径的性能结果已清理，不能与本报告混用。

## 8. 关键结论

- 基于 Caffeine + Redis 构建活动热点两级缓存；本地单实例 100 并发、三轮 65 秒基准中，查询吞吐由 MySQL 直查约 4.3k 提升至约 12.8k QPS，P99 由 44 ms 降至 10 ms，Caffeine 命中率约 99.99%。
- 将活动校验、库存扣减、一人一单判重与 pending 写入收敛至 Redis Lua 原子执行；本地单实例 50 并发、三轮 65 秒基准稳定约 690 QPS、P99 108 ms，零系统错误，无超卖、重复领取和 pending 残留。
- 通过 publisher confirm 与 pending 状态流转协调首次投递和补偿重试，并以消费端幂等保证至少一次投递语义；5,000 独立用户验收中生成 5,000 有效订单，无重复订单，pending 与 DLQ 清零。

这些结论仅适用于同机、本地、单实例基准环境，不能外推为生产集群容量。
