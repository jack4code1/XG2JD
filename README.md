# AI 驱动的高并发优惠券秒杀平台

> 面向商户运营场景的优惠活动平台：商户可用自然语言生成活动草稿，在人工确认后受控执行；用户侧通过 Redis Lua 完成高并发抢券，订单异步落库并具备失败补偿能力。

这是一个用于展示 Java 后端工程能力的完整项目，而不只是一个秒杀接口示例。重点解决三类问题：**高并发下不超卖、不重复领**、**Redis 与 MQ 之间的投递可靠性**，以及 **AI 生成运营动作时的权限、确认与审计边界**。

## 项目亮点

- **AI 活动创建与受控发布**：自然语言需求被解析为包含优惠金额、库存、有效期、限领规则的结构化 Proposal；商户确认前不写业务数据，确认后仅允许创建活动、补库存、暂停、恢复等白名单动作。任务、动作和执行结果均持久化审计。
- **Redis Lua 原子抢券**：在一次脚本调用中完成活动状态/时间窗口校验、库存扣减、一人一单判重与待投递订单记录；Bloom Filter 仅作性能预筛，Redis Set 才是最终判重依据。
- **异步订单与可靠补偿**：Lua 原子受理后写入 Redis pending 记录；publisher confirm 后等待消费者事务提交确认才清理。确认超时、初始投递失败或进程异常时，定时任务按指数退避重投，消费者按订单号幂等落库。
- **活动生命周期感知缓存**：优惠券静态配置采用 Caffeine L1 + Redis L2；活动变更写入不可变版本快照，并通过 Redis 指针原子切换。热点缓存采用逻辑过期与异步刷新，库存与领取资格始终走 Redis 实时校验。
- **并发状态控制与可观测性**：商品订单使用状态机与 JPA 乐观锁控制支付、取消、退款、核销等并发迁移；提供 Actuator、Prometheus 指标、通知中心和生命周期/补偿调度任务。

## 架构概览

```text
商户自然语言需求
        │
        ▼
AI Proposal（持久化） ── 人工确认 ──► 白名单运营动作 ──► 活动版本快照 / 缓存切换

用户抢券请求
        │
        ▼
Bloom Filter / 风控策略 ──► Redis Lua 原子校验、扣库存、判重、写 pending
                                                       │
                                                       ▼
                                            RabbitMQ 异步下单
                                                       │
                                                       ▼
                                      幂等消费者 ──► MySQL 订单
                                                       ▲
                         publisher confirm / pending 补偿重投 ──┘
```

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.2、Spring MVC、Spring Data JPA |
| 前端 | React 19、Vite、Tailwind CSS |
| 数据 | MySQL 8、Redis 7、Caffeine、Redisson |
| 消息与任务 | RabbitMQ 3.12、Spring Scheduling、本地消息表 |
| 并发与治理 | Redis Lua、Guava Bloom Filter、Sentinel、JPA `@Version` |
| AI 与观测 | Spring AI（兼容 DeepSeek API）、Actuator、Micrometer、Prometheus |
| 部署 | Docker Compose（应用 + MySQL + Redis + RabbitMQ） |

## 快速启动（推荐 Docker Compose）

### 前置条件

- Docker Desktop（Windows/macOS）或 Docker Engine + Compose（Linux）
- 首次构建需要访问镜像与 Maven 依赖仓库；项目默认配置了国内 Docker 镜像代理和 Maven 镜像

在仓库根目录执行：

```bash
docker compose up -d --build
docker compose ps
```

待 `app` 显示运行后访问：

| 地址 | 用途 |
| --- | --- |
| `http://localhost:8080/` | Web 页面 |
| `http://localhost:8080/api/ai/health` | 应用健康检查 |
| `http://localhost:15672/` | RabbitMQ 管理台（`admin` / `admin123`） |

查看启动日志：

```bash
docker compose logs -f app
```

停止服务但保留 MySQL 数据卷：

```bash
docker compose down
```

启用 Prometheus 和 Grafana：

```bash
docker compose --profile observability up -d
```

可选地设置环境变量以覆盖默认开发配置（PowerShell 示例）：

```powershell
$env:DEEPSEEK_API_KEY = "你的 API Key"
$env:JWT_SECRET = "不少于 32 字符的随机密钥"
docker compose up -d --build
```

> 默认密码仅用于本地开发。生产部署请通过环境变量设置数据库、RabbitMQ、JWT 和 AI 密钥；`prod` Profile 会拒绝示例默认密钥。

应用配置不再提供数据库、RabbitMQ、JWT 或 AI 密钥默认值。启动前必须设置：

```text
SPRING_DATASOURCE_URL  MYSQL_USER  MYSQL_PASSWORD
RABBITMQ_USER          RABBITMQ_PASSWORD
JWT_SECRET             DEEPSEEK_API_KEY
```

## 演示账号

初始化数据中所有账号密码均为 `123456`。

| 角色 | 账号 | 店铺 |
| --- | --- | --- |
| 商户 | `merchant_food` | 火焰小食铺 |
| 商户 | `merchant_fashion` | 拾光衣橱 |
| 用户 | `test_user` | - |
| 用户 | `test_user_2` | - |

## 核心设计

### 1. Redis Lua：库存正确性与一人一单

抢券脚本把下列步骤放在同一个 Redis 原子单元中：

1. 校验活动状态和有效时间窗口；
2. 使用 Redis Set 做最终一人一单判重；
3. 校验并扣减库存；
4. 写入用户领取标记；
5. 写入带唯一订单号的 pending 记录，并加入待投递索引。

因此并发请求不会在“检查库存”和“扣减库存”之间穿插。Bloom Filter 只减少明显重复请求的后续查询，不参与最终正确性判定。

### 2. Redis → MQ 的投递空窗补偿

Redis 原子扣库存后，如果进程在发送 MQ 前崩溃，单纯依赖 publisher confirm 仍会丢失订单。这里采用 pending 记录补齐该空窗：

```text
Lua 原子扣减 + 写 pending
        │
        ├── RabbitMQ 确认成功 ──► 删除 pending
        └── 失败 / 超时 / 崩溃 ──► 调度器指数退避重投 ──► 幂等消费者落库
```

重投任务使用分布式锁避免多实例重复扫描；达到最大次数后记录终态并通知商户，保留订单号用于人工核对。

### 3. 活动配置与实时数据分治

```text
优惠券静态规则：Caffeine L1 → Redis L2 版本快照 → MySQL
热点规则：逻辑过期 + 异步刷新 + Redisson 单飞重建
实时库存/资格：Redis Lua（不走本地缓存）
```

活动修改不会直接覆盖缓存对象：先落库生成不可变 `coupon_version` 快照，再原子切换 Redis active 指针。请求只会读取旧完整版本或新完整版本，避免看到半更新配置。

### 4. AI 执行边界

```text
自然语言 → 结构化 Proposal → 商户确认 → 条件更新抢占执行权 → 白名单工具调用 → 审计/通知
```

- Proposal 在确认前仅保存，不直接执行；
- 确认接口通过条件更新抢占任务，避免多实例或重复点击重复执行；
- 长时间卡在执行中的 AI 任务会被恢复调度器标记为待人工处理；
- AI 调用审计仅记录必要元数据，不保存完整模型原文。

## 关键接口

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/auth/login` | `POST` | 登录并获取 Redis 共享会话 Token |
| `/api/coupon/{couponId}` | `GET` | 查询活动详情（版本化分层缓存） |
| `/api/seckill/execute` | `POST` | 发起抢券，返回订单号用于轮询 |
| `/api/seckill/result/{orderNo}` | `GET` | 查询异步下单结果（仅本人） |
| `/api/ai/tasks` | `POST` / `GET` | 创建与查询 AI 运营任务（商户） |
| `/api/ai/tasks/{taskNo}/confirm` | `POST` | 人工确认并执行 Proposal（商户） |
| `/api/coupon/{couponId}/versions` | `GET` | 查询活动版本历史（商户） |
| `/api/coupon/{couponId}/rollback/{version}` | `POST` | 基于历史快照发布新版本（商户） |
| `/api/notifications` | `GET` | 查询当前用户通知 |

完整接口与排障说明见 [项目交接文档](docs/PROJECT_HANDOFF.md)。

## 本地开发（不使用 Docker）

需要本机启动 MySQL、Redis、RabbitMQ，并导入初始化脚本：

```bash
# 导入数据库（按本机账号和端口调整）
mysql -u root -proot123 -h 127.0.0.1 -P 3306 < src/main/resources/sql/init.sql

# 后端构建与启动
mvn -DskipTests package
java -jar target/seckill-coupon-1.0.0.jar
```

前端开发服务器：

```bash
cd frontend
npm install
npm run dev
```

## 项目结构

```text
src/main/java/com/seckill/
├── cache/       # 分层缓存、热点探测、版本快照
├── config/      # Redis、RabbitMQ、鉴权、监控配置
├── controller/  # REST API
├── model/       # JPA 实体、订单状态机
├── scheduler/   # 活动生命周期、pending 订单、AI 任务恢复、消息补偿
├── service/     # 秒杀、订单、AI 执行、通知等核心业务
├── strategy/    # 风控和用户分配策略链
└── util/        # JWT、用户上下文、设备指纹

src/main/resources/
├── lua/check_qualify.lua  # 原子抢券脚本
└── sql/                   # 初始化与演示数据
```

## 进一步阅读

- [项目交接文档](docs/PROJECT_HANDOFF.md)：完整链路、配置、排障说明
- [并发实验报告](docs/BENCHMARK_REPORT.md)：压测方法与历史实验记录

---

如果这个项目对你有帮助，欢迎 Star。也欢迎在 Issue 中讨论并发一致性、缓存更新和 AI 受控执行的实现细节。
