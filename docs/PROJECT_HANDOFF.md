# 项目交接文档

更新时间：2026-08-19
项目路径：以当前机器仓库路径为准（本次验证为 `D:\code\XG2JD`）
GitHub：`https://github.com/jack4code1/XG2JD`

## 1. 项目定位

这是一个面向校招简历的高并发优惠券秒杀系统，同时包含 AI 运营 Copilot。项目重点展示：

- Redis + Lua 原子秒杀
- RabbitMQ 异步削峰和订单最终一致性
- Sentinel 限流、防刷和智能用户策略
- 商户与普通用户角色隔离
- 商品支付、优惠券抵扣和订单状态机
- 基于真实 MySQL / Redis 数据的 Multi-Agent 运营分析与受控执行

## 2. 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.2、Spring MVC、Spring Data JPA |
| 数据库 | MySQL 8.4 |
| 缓存 | Redis 8.10、Caffeine、Redisson |
| 消息 | RabbitMQ 4.3 |
| 限流 | Sentinel 1.8 |
| AI | Spring AI、DeepSeek Chat、4 个并行 Agent |
| 前端 | React、Vite、Axios |
| 观测 | Actuator、Micrometer、Prometheus |

## 3. 本地启动

启动依赖：

```bash
brew services start mysql@8.4
brew services start redis

CONF_ENV_FILE="/opt/homebrew/etc/rabbitmq/rabbitmq-env.conf" \
  /opt/homebrew/opt/rabbitmq/sbin/rabbitmq-server -detached
```

初始化数据库：

```bash
mysql -u root -proot123 -h 127.0.0.1 -P 3306 < src/main/resources/sql/init.sql
mysql -u root -proot123 -h 127.0.0.1 -P 3306 < src/main/resources/sql/test-data.sql
redis-cli FLUSHDB
```

构建和启动：

```bash
mvn -q -DskipTests package
java -jar target/seckill-coupon-1.0.0.jar
```

前端开发服务：

```bash
cd frontend
npm run dev
```

访问地址：

- 前端：`http://localhost:3000`
- 后端：`http://localhost:8080`
- 健康检查：`GET /api/ai/health`
- Actuator：`http://localhost:8080/actuator`

## 4. 测试账号

密码统一为 `123456`。

| 角色 | 账号 | 店铺 |
| --- | --- | --- |
| 商户 | `merchant_food` | 火焰小食铺 |
| 商户 | `merchant_fashion` | 拾光衣橱 |
| 用户 | `test_user` | - |
| 用户 | `test_user_2` | - |
| 用户 | `test_user_3` | - |

商户只能访问自己的店铺、商品、优惠券、订单经营数据和 AI Copilot；普通用户只能访问商城、自己的优惠券和订单。

## 5. 秒杀链路

```text
JWT 登录
  -> 设备指纹 / Redis ZSET 滑动窗口 / Sentinel
  -> Bloom Filter 性能预筛
  -> 用户分配策略链
  -> Redis Lua 原子校验、扣库存、标记用户
  -> RabbitMQ 发布确认
  -> 异步创建 MySQL 订单
  -> GET /api/seckill/result/{orderNo} 轮询结果
```

Redis Lua 在同一个脚本中完成：

1. 活动时间校验
2. 一人一单校验
3. 库存预检和扣减
4. Redis Set 标记用户

Bloom Filter 只用于减少重复请求的精确查询，Redis Set 是最终判断依据。成功后订单消息带有唯一 `orderNo`，消费者按订单号幂等。

关键代码：

- `src/main/java/com/seckill/service/SeckillService.java`
- `src/main/resources/lua/check_qualify.lua`
- `src/main/java/com/seckill/service/OrderCreateConsumer.java`
- `src/main/java/com/seckill/controller/SeckillController.java`

## 6. 消息可靠性

- RabbitMQ 开启 correlated publisher confirm。
- 发布确认最多等待 2 秒。
- 确认超时记录 `seckill.mq.confirm.timeout`，不盲目回补库存，避免确认包延迟造成重复库存。
- 发布失败记录 `seckill.mq.publish.failure`。
- 消费者使用订单号幂等。
- 订单状态事件使用 `event_log` 本地消息表和指数退避重试。
- RabbitMQ 队列配置死信交换机。
- 自定义 `rabbitListenerContainerFactory` 必须通过 `SimpleRabbitListenerContainerFactoryConfigurer` 初始化，否则 `spring.rabbitmq.listener.simple.*` 的并发和 prefetch 配置不会生效。2026-08-20 JMeter 实验已发现并修复该问题。

初始秒杀消息采用 Redis pending 记录补偿：Lua 在扣减库存、标记用户的同一原子单元中写入待投递订单；发布确认成功后删除 pending 记录，确认超时、发布失败或进程在发布前崩溃时由定时任务按指数退避重投。重复投递由订单号幂等消费兜底。该方案依赖 Redis AOF 持久化；跨 Redis 集群和 MQ 的严格分布式事务仍需专用事务消息或更高层协调。

## 7. 商品支付和优惠券

优惠券领取免费，不产生支付金额；商品订单才需要支付。用户可以在商品结算时选择已领取、同店铺且未使用的优惠券。

商品订单金额：

```text
实付金额 = max(商品原价 - 优惠券抵扣金额, 0)
```

订单状态：

```text
CREATED -> PAYING -> PAID -> USED
    |                  |
 CANCELED           REFUNDING -> REFUNDED
    |
 EXPIRED
```

当前支付为模拟支付状态机，不接入真实微信或支付宝网关。前端商品订单支持查看详情，包括消费时间、支付完成时间、原价、抵扣和实付金额。

商家工作台的商品与优惠券列表均可点击进入管理弹窗：

- `PUT /api/product/{productId}`：编辑名称、介绍、价格、库存及上下架状态。
- `PUT /api/coupon/{couponId}`：编辑名称、说明、优惠金额、限领数、结束时间，追加库存或暂停/恢复活动。
- 两个接口都会校验资源属于当前商户；优惠券修改会同步 MySQL 与 Redis，暂停状态会被秒杀 Lua 在扣库存前拦截。
- 商家工作台返回全部本店商品（含已下架），用户商城仍只展示销售中的商品。

## 8. AI Copilot 与执行 Agent

流程：

```text
商户自然语言问题
  -> MySQL / Redis 真实数据快照
  -> 意图识别
  -> 数据、风控、内容、策略 4 Agent 并行
  -> 持久化不可变 Proposal
  -> 商户确认
  -> 白名单业务工具执行
  -> MySQL / Redis 同步 + 动作审计
```

主要接口：

| 接口 | 说明 |
| --- | --- |
| `POST /api/ai/copilot/query` | 查询实时经营分析和 Agent 结果 |
| `POST /api/ai/copilot/execute` | 商户确认后执行创建优惠券 |
| `POST /api/ai/eval` | 运行 4 条固定评测问题 |
| `GET /api/ai/audits` | 查询当前商户最近 20 次调用审计 |
| `POST /api/ai/tasks` | 将自然语言目标转换为待确认任务 |
| `GET /api/ai/tasks` | 查询当前商户最近 20 个任务和动作时间线 |
| `POST /api/ai/tasks/{taskNo}/confirm` | 幂等确认并执行保存的 Proposal |
| `POST /api/ai/tasks/{taskNo}/cancel` | 取消待确认任务 |

AI 审计表 `ai_audit_log` 只保存商户、问题、意图、耗时和降级状态，不保存完整模型原文。

Copilot 降级时会使用本地 MySQL / Redis 快照规则生成结果，并返回 `degraded=true`。

执行 Agent 当前开放 4 个工具：`CREATE_CAMPAIGN`、`INCREASE_STOCK`、`PAUSE_CAMPAIGN`、`RESUME_CAMPAIGN`。每个任务保存商户归属、原始指令、Proposal、执行结果和时间戳；每个动作保存输入、状态、结果或错误。暂停状态为优惠券 `status=3`，秒杀 Lua 会在扣库存之前返回 `-4` 拦截请求。

安全边界：模型不直接写数据库；高风险写操作均需 Human-in-the-loop 确认；确认时执行已保存参数，不再次调用模型改变方案；已完成任务重复确认不会重复写入。确认阶段使用 `WHERE status = WAITING_CONFIRMATION` 的数据库条件更新原子抢占任务，确保多实例下只有一个执行者进入白名单工具链。

## 9. 观测指标

可通过 `/actuator/metrics` 和 Prometheus 查看：

```text
seckill.requests
seckill.mq.confirm.timeout
seckill.mq.publish.failure
ai.copilot.requests
ai.copilot.degraded
seckill.hotkey.count
seckill.caffeine.hit.rate
```

## 10. 压测和验收

最新实测报告见 `docs/BENCHMARK_REPORT.md`。JMeter 脱敏汇总位于 `docs/jmeter-results-2026-08-20.json`，测试计划为 `scripts/jmeter/seckill-load.jmx`，夹具与结果汇总工具为 `scripts/jmeter_benchmark.py`。历史 Python 原始 JSON 位于 `docs/benchmark-results-2026-08-19*.json`，脚本为 `scripts/benchmark_matrix.py`。

2026-08-20 JMeter 5.6.3 单机实测摘要：

- 200 并发、200 req/s 持续 10 分钟，共 120,000 请求：HTTP 与业务成功率 100%，P50 48 ms、P95 77 ms、P99 104 ms，0 重复订单号。
- MQ 清空后，30 个活动的 MySQL 订单数和唯一订单号均为 120,000，Redis 库存全部为 0，死信队列为 0。
- 库存 100、200 并发、1,000 请求：仅成功 100；同一用户 100 并发、100 请求：仅成功 1。
- 容量上探以“业务成功率 100% 且 P99 < 2 秒”为标准，最高稳定验证并发为 200；400 并发出现 30 次连接拒绝，800 并发错误率 4.95%。
- 稳定性测试暴露自定义监听器工厂未应用 Boot 消费参数：修复前实际仅 1 个消费者、恢复约 26.4 orders/s；修复后运行时 20–40 消费者的峰值恢复约 117.8 orders/s。
- 稳定性实验的 199.89 req/s 是受控输入速率，不是系统最大 QPS；容量实验与 JMeter、应用、中间件同机，不能外推为生产集群能力。

2026-08-19 单机实测摘要：

- 10/50/100/200 并发矩阵独立执行 3 轮，共 4,800 请求，成功率 100%，0 重复订单、0 MQ 落库缺失。
- 200 并发、2,000 请求持续实验：419.44 req/s，P50 462.84 ms，P99 491.31 ms。
- 库存 100、300 请求售罄实验执行 4 轮，每轮仅成功 100，最终库存 0。
- 同一用户 50 并发、100 请求执行 4 轮，每轮仅成功 1，最终库存 99。
- 全部实验的 Prometheus 成功计数、MySQL 订单数和唯一订单号数量均为 7,324。

准备 `/tmp/seckill_tokens.txt` 后执行：

```bash
python3 scripts/bench_pure.py <couponId> <并发数> <请求数>
```

JMeter 复现入口：

```powershell
python scripts/jmeter_benchmark.py prepare-suite --users 4000 --output target/jmeter-data
jmeter -n -t scripts/jmeter/seckill-load.jmx -Jdata_file=<csv> -Jthreads=200 -Jloops=600
```

JTL 会包含临时 Token，只能保存在 Git 已忽略的 `target/`，不得直接提交；仓库只保留脱敏 JSON 汇总。

脚本输出成功数、Redis 剩余库存、P50/P99、重复订单号和 PASS/FAIL。验收标准：

- 成功订单数不超过库存数
- 重复订单号为 0
- Redis 库存不小于 0
- MQ 消费后 MySQL 库存与 Redis 最终一致

AI 固定评测：

```bash
curl -X POST http://localhost:8080/api/ai/eval \
  -H "Authorization: Bearer <merchant-token>"
```

2026-08-19 本机验证：`mvn test` 通过 4/4，`mvn -DskipTests package`、`mvn test-compile` 和前端 `npm run build` 均通过。Lua 原子契约测试位于：

`src/test/java/com/seckill/service/SeckillLuaAtomicContractTest.java`

## 11. 常见问题

### 抢券提示活动未开始或已结束

前端会显示活动开始或结束时间。后端以 Redis 中的 `start_time`、`end_time` 毫秒时间戳为准。创建或重置数据后，需要重新预热进行中的优惠券。

### 订单列表暂时没有新订单

秒杀订单通过 RabbitMQ 异步落库，前端会轮询结果接口。检查 RabbitMQ 服务、`order.create.queue` 和后端日志中的 `订单创建成功`。

### DeepSeek 不可访问

确认 `DEEPSEEK_API_KEY` 和网络代理配置。即使外部模型不可用，Copilot 也会切换本地降级策略。

### 停止本地后端时出现 Logback ThrowableProxy 异常

当前环境下偶发于应用关闭阶段，不影响启动和业务请求；启动日志出现 `Tomcat started on port 8080` 即代表服务正常。

## 12. 版本记录

| Commit | 内容 |
| --- | --- |
| `12b1492` | RabbitMQ 发布确认、AI 审计、评测和指标 |
| `253522d` | 秒杀 Lua 原子提交、结果查询、前端轮询 |
| `834641b` | 商品订单详情 |
| `ff487f6` | 商品订单和优惠券订单分离 |

对应语雀文档：`tongtaixin.ttx/fy06pg/ptaa84vaki0spf7g`。

给新的 AI coding agent 使用时，同时提供 `docs/AI_BOOTSTRAP.md`、本文件和 `README.md`。环境变量模板见仓库根目录 `.env.example`。
