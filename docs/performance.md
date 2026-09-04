# 性能测试说明

## 范围与边界

本仓库保留的是 2026-08-28 在本机、单 Spring Boot 实例上的历史基准结果。它们用于说明脚本和测试方法可追溯，不代表生产容量，也不应被表述为多机、集群或高可用结果。

完整原始 JTL 未提交，因为可能含短期访问 Token；仓库保留脱敏报告 `docs/PERFORMANCE_TEST_HANDOVER.md`、最终验收摘要 `performance-test/results/p0-20260828b-final-report.md` 与复现脚本。

## 已记录环境

| 项目 | 记录值 |
| --- | --- |
| 应用 | 单个本地 Spring Boot 实例 |
| JDK | Microsoft OpenJDK 17.0.19 LTS |
| JVM | `-Xms512m -Xmx512m -XX:+UseG1GC -XX:TieredStopAtLevel=1` |
| 压测工具 | JMeter 5.6.3，与服务端同机 |
| MySQL | 8.0.46 |
| Redis | standalone 8.10.1 |
| RabbitMQ | 3.12.14 |

原报告还记录了主机为 macOS arm64、12 CPU 核、16 GiB 内存；换机器、JDK、数据规模或部署拓扑后都应重新测试。

## 可复现入口

前提：本地启动 MySQL、Redis、RabbitMQ 和启用 `perf` profile 的应用；安装 Python 3、JMeter，并设置测试账号密码。

```sh
export PERF_MERCHANT_PASSWORD='<local-merchant-password>'
./perf/run-cache-matrix.sh cache_<date>
./performance-test/scripts/run-lua-matrix.sh lua_<date>
python3 performance-test/scripts/run-p0-stability.py cache --run-id p0c_<date> --app-pid <pid>
python3 performance-test/scripts/run-p0-stability.py lua --run-id p0l_<date> --app-pid <pid>
```

运行产物写入已忽略的 `target/perf-cache/` 和 `performance-test/results/`。提交前应检查其中不含 Token、密码或 JTL。

## 已记录结果摘要

所有数字均来自上面所述历史环境与 `docs/PERFORMANCE_TEST_HANDOVER.md`。

| 场景 | 并发 | 记录结果 |
| --- | ---: | --- |
| 活动详情 MySQL 直查 | 100 | 4314.30 QPS，P99 44 ms |
| Redis 缓存读取 | 100 | 10538.42 QPS，P99 12 ms |
| Caffeine + Redis | 100 | 12754.77 QPS，P99 10 ms |
| Redis Lua 完整受理路径 | 50 | 690.41 QPS，P99 108 ms，系统错误率 0.0000% |

原报告的 Lua 稳定性审计说明：当时 12 轮测试中，成功受理数与订单数一致、无负库存、无重复领取、pending 清零。该结论仅覆盖报告记录的测试数据与故障注入条件。

RabbitMQ 验收记录为 5,000 个独立用户最终生成 5,000 个有效订单，无重复业务订单，pending 与死信队列清零；未记录同步下单对照或 RabbitMQ 吞吐对比，因此不能推导出“MQ 提升多少 QPS”之类结论。
