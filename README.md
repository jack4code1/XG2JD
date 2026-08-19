# 高并发优惠券秒杀系统

面向校招简历的秒杀项目，聚焦三个差异化：**热点自动发现与动态缓存降级**、**智能分配策略**、**全链路故障演练**。

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

## 核心架构

### 秒杀全链路（5层漏斗）
```
Bloom Filter 预筛 → 策略权重计算 → Lua 资格校验 → Lua 原子扣库存 → MQ 异步下单
    1.2MB             4策略链            <5ms              <3ms           异步
```

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
| `/api/seckill/execute` | POST | 秒杀（需登录） |
| `/api/order/{orderNo}` | GET | 查询订单 |

## 压测

```bash
# 生成压测用户
python3 -c "..."  # 见 scripts/bench.py

# 纯秒杀压测
python3 scripts/bench_pure.py <couponId> <并发数> <请求数>

# 含登录的完整压测
python3 scripts/bench.py <并发数> <请求数> <couponId>
```

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