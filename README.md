# 高并发优惠券秒杀系统

面向校招简历的秒杀项目，聚焦三个差异化：**高并发秒杀与最终一致性**、**可确认可审计的 AI 运营执行 Agent**、**热点自动发现与动态缓存降级**。

完整启动、链路、接口、压测和排障信息见：[项目交接文档](docs/PROJECT_HANDOFF.md)；交给新的 AI coding agent 时同时提供：[AI 接手指令](docs/AI_BOOTSTRAP.md)。

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 17 |
| Spring Boot | 3.2.0 |
| MySQL | 8.4 |
| Redis | 8.10 |
| RabbitMQ | 4.3.5 |
| Caffeine | 3.x |
| Sentinel | 1.8.6 |
| Redisson | 3.24.3 |

## 快速启动

### 1. 启动中间件
```bash
# MySQL (已通过 Homebrew 安装)
brew services start mysql@8.4

# Redis
brew services start redis

# RabbitMQ (手动启动)
CONF_ENV_FILE="/opt/homebrew/etc/rabbitmq/rabbitmq-env.conf" \
  /opt/homebrew/opt/rabbitmq/sbin/rabbitmq-server -detached

# 创建 admin 用户
rabbitmqctl add_user admin admin123
rabbitmqctl set_user_tags admin administrator
rabbitmqctl set_permissions -p / admin ".*" ".*" ".*"
```

### 2. 初始化数据库
```bash
mysql -u root -proot123 -h 127.0.0.1 -P 3306 < src/main/resources/sql/init.sql
```

### 3. 构建运行
```bash
mvn package -DskipTests
java -jar target/seckill-coupon-1.0.0.jar
```

### 本地测试账号

执行 `mysql -u root -proot123 -h 127.0.0.1 -P 3306 < src/main/resources/sql/test-data.sql` 可重置为以下数据，密码均为 `123456`：

重置后执行 `redis-cli FLUSHDB`，再启动后端并为进行中的优惠券预热 Redis。

| 类型 | 账号 | 店铺 |
|------|------|------|
| 商户 | `merchant_food` | 火焰小食铺 |
| 商户 | `merchant_fashion` | 拾光衣橱 |
| 用户 | `test_user` | - |
| 用户 | `test_user_2` | - |
| 用户 | `test_user_3` | - |

## 核心架构

### 秒杀全链路（5层漏斗 + 原子提交）
```
Bloom Filter 预筛 → 策略权重计算 → Lua原子校验/扣库存/标记用户 → MQ异步下单 → 结果轮询
    性能预筛          4策略链              单脚本提交            削峰落库       CREATED/失败
```

秒杀 Lua 脚本将活动时间校验、一人一单校验、库存扣减和用户标记放在同一次 Redis 原子执行中，避免并发请求在“检查”和“标记”之间产生重复订单。Bloom Filter 只承担性能预筛，Redis Set 是最终一致性判断依据。

### 策略链
```
AntiFraudStrategy(P0) → NewUserStrategy(P1) → DormantUserStrategy(P2) → DefaultStrategy
   ZSET滑动窗口          新用户+50权重          沉睡用户+30权重           先到先得+100
```

### 热点发现
```
环形缓冲区 [12槽 × 5s = 60s窗口]
  → 热点判定(QPS>100) → Caffeine 升级 refreshAfterWrite(30s)
  → 热度下降(连续3轮) → 降级 expireAfterWrite(5min)
```

### 订单一致性
```
状态机: CREATED → PAYING → PAID → USED/REFUNDING → REFUNDED
         ↓         ↓
      CANCELED / EXPIRED(15min)

本地消息表 + 指数退避(1s→2s→4s…→30s) + @Version乐观锁 + T+1对账
```

## API 文档

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/auth/register` | POST | 注册 |
| `/api/auth/login` | POST | 登录 |
| `/api/auth/refresh` | POST | 刷新Token |
| `/api/auth/logout` | POST | 登出 |
| `/api/coupon/create` | POST | 创建优惠券（需登录） |
| `/api/coupon/{couponId}` | PUT | 编辑本店优惠券、追加库存、暂停/恢复并同步 Redis（商家） |
| `/api/product/{productId}` | PUT | 编辑本店商品、库存和上下架状态（商家） |
| `/api/seckill/execute` | POST | 秒杀（需登录） |
| `/api/seckill/result/{orderNo}` | GET | 查询异步秒杀订单结果（需登录且只能查本人） |
| `/api/order/{orderNo}` | GET | 查询订单 |
| `/api/ai/eval` | POST | 运行 4 条 Copilot 固定评测问题（商家） |
| `/api/ai/audits` | GET | 查询当前商户最近 20 次 AI 调用审计 |
| `/api/ai/tasks` | POST/GET | 创建执行任务 / 查询最近任务（商家） |
| `/api/ai/tasks/{taskNo}/confirm` | POST | 确认不可变 Proposal 并执行白名单工具 |
| `/api/ai/tasks/{taskNo}/cancel` | POST | 取消尚未执行的任务 |

## 压测

```bash
# 生成压测用户
python3 -c "..."  # 见 scripts/bench.py

# 纯秒杀压测
python3 scripts/bench_pure.py <couponId> <并发数> <请求数>

# 含登录的完整压测
python3 scripts/bench.py <并发数> <请求数> <couponId>
```

纯秒杀压测脚本会输出成功数、Redis 剩余库存和重复订单号数量。库存为 `N` 时，成功订单数不应超过 `N`，重复订单号应为 `0`。执行前请确认优惠券活动时间有效，并准备 `/tmp/seckill_tokens.txt`。

### AI 评测与观测

商家登录后可调用 `POST /api/ai/eval`，固定验证分析、风控、活动策划和普通问答四类意图，同时检查结构化字段是否完整。每次 Copilot 调用会记录商户、问题、意图、耗时和是否降级到 `ai_audit_log`，不保存完整模型原文。

AI 执行台支持创建活动、追加库存、暂停活动和恢复活动。自然语言先转换为持久化 Proposal，状态为 `WAITING_CONFIRMATION`；只有当前商户确认后才调用白名单业务工具。Proposal 在确认时不会重新生成，重复确认已完成任务不会重复写数据，任务结果和动作时间线分别保存在 `ai_task`、`ai_action`。

Prometheus 指标包括：

```text
seckill_requests_total{result="success"}
seckill_mq_confirm_timeout_total
seckill_mq_publish_failure_total
ai_copilot_requests_total
ai_copilot_degraded_total
```

RabbitMQ 开启 correlated publisher confirm；消费者继续使用订单号幂等，发布确认超时时保留订单结果轮询，不盲目回补库存，避免“消息已到达但确认包延迟”造成重复库存。

### 压测结果
```
🏆 单机 QPS: 359 req/s  |  P50: 98ms  |  P99: 645ms  |  成功率: 100%
📈 峰值 QPS: 516 req/s  |  P50: 45ms  |  库存扣减: 100%准确
```

## 故障演练

```bash
# Redis 宕机
brew services stop redis; sleep 10; brew services start redis

# MQ 积压
rabbitmqctl stop_app; sleep 5; rabbitmqctl start_app

# DB 慢查询
mysql -u root -proot123 -h 127.0.0.1 -e "SELECT SLEEP(10)" &
```

详见 `fault-drill-report.md`

## 项目结构

```
src/main/java/com/seckill/
├── cache/          # 热点发现 + 缓存管理
├── config/         # Redis/RabbitMQ/Sentinel/WebMvc
├── controller/     # REST API
├── dto/            # 请求/响应对象
├── model/          # JPA 实体 + 状态机枚举
├── repository/     # JPA Repository
├── scheduler/      # 定时任务（消息表扫描/订单过期）
├── service/        # 核心业务逻辑
├── strategy/       # 智能分配策略（策略模式）
└── util/           # JWT/UserContext/DeviceFingerprint
```

## 踩坑记录

1. **Redis 8.x Lua 兼容性**：`tonumber(HGET ...)` 返回值精度异常，使用 `HINCRBY key field 0` 读取整数字段 + 等长字符串比较时间戳
2. **RabbitMQ 4.x 消费者**：必须显式配置 `Jackson2JsonMessageConverter` + `RabbitListenerContainerFactory`，否则消息反序列化失败进 DLQ
3. **MySQL 8.4 Homebrew**：默认不开启 TCP 端口，需确认 `bind-address = 127.0.0.1` 且无 `skip-networking`
4. **Redis Hash 整数字段**：`opsForHash().put(key, field, int)` 存为 Redis Integer，`putAll(Map)` 全部存为 String
