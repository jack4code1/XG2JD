# AI 接手指令

将本文件、`README.md` 和 `docs/PROJECT_HANDOFF.md` 一起提供给新的 AI coding agent。它们是项目背景、启动方式和协作边界的入口。

## 项目身份

这是一个 Java 17 + Spring Boot 3.2 + React/Vite 的优惠券秒杀系统，包含：

- Redis Lua 原子秒杀、RabbitMQ 异步订单和 MySQL 持久化
- Sentinel 限流、设备指纹、防刷和用户策略链
- 商户和普通用户角色隔离
- 商品支付、优惠券抵扣和订单状态机
- 基于真实 MySQL/Redis 快照的 AI Multi-Agent 运营 Copilot

仓库路径以当前机器为准，不要假设一定是 `/Users/jackt/seckill-coupon`。先执行：

```bash
pwd
git status --short
git log -3 --oneline
```

## 接手顺序

1. 阅读 `README.md`。
2. 阅读 `docs/PROJECT_HANDOFF.md`。
3. 检查 `src/main/resources/application.yml`、`src/main/resources/sql/init.sql` 和 `src/main/resources/sql/test-data.sql`。
4. 检查 MySQL、Redis、RabbitMQ 是否可用。
5. 确认前后端端口：后端 `8080`，前端 `3000`。
6. 修改代码前先检查相关模块和现有未提交改动，不要覆盖用户的工作。

## 配置

复制 `.env.example`，再根据本机环境设置变量：

```bash
export MYSQL_USER=root
export MYSQL_PASSWORD=your-password
export RABBITMQ_USER=admin
export RABBITMQ_PASSWORD=your-password
export DEEPSEEK_API_KEY=your-key
export JWT_SECRET=your-random-secret-at-least-32-bytes
```

不要把真实密钥写入代码、README、交接文档、提交记录或聊天内容。DeepSeek 不可访问时，AI Copilot 应保留本地降级能力。

## 运行和验证

```bash
mvn -q -DskipTests package
cd frontend && npm run build
```

完整服务启动顺序：

```text
MySQL -> Redis -> RabbitMQ -> init/test data -> backend -> frontend
```

重置测试数据会改变数据库和 Redis 状态，执行前必须确认用户允许：

```bash
mysql -u root -p -h 127.0.0.1 -P 3306 < src/main/resources/sql/init.sql
mysql -u root -p -h 127.0.0.1 -P 3306 < src/main/resources/sql/test-data.sql
redis-cli FLUSHDB
```

默认测试账号密码为 `123456`，详见交接文档。测试数据、Redis 库存、MQ 队列和订单是动态状态，不能假设与另一台电脑一致。

## 修改边界

- 不要把优惠券领取当成支付订单；优惠券免费领取，商品订单才支付。
- 不要让普通用户访问商户管理、其他用户订单或其他商户数据。
- AI 只能提出建议；创建优惠券等写操作必须商户确认。
- 支付是模拟支付状态机，不要描述成已接入真实微信/支付宝。
- 秒杀资格校验、扣库存、用户标记必须保持在同一个 Lua 原子脚本中。
- MQ 消息必须保持发布确认、订单号幂等和失败可观测。
- 查询接口必须校验当前登录用户或商户归属。

## 修改后必做

```bash
git diff --check
mvn -q -DskipTests package
mvn -q -DskipTests test-compile
cd frontend && npm run build
```

如果完整 `mvn test` 因外部 Maven 镜像或网络失败，必须在交接记录中说明原因，不能把“跳过测试的构建”写成“测试通过”。

完成业务改动后同步维护：

- `README.md`
- `docs/PROJECT_HANDOFF.md`
- 语雀项目交接文档

提交前检查 `git status --short`，只提交本次相关文件。用户明确要求时再执行 `git push origin main`。

## 当前已知限制

- 完整 Outbox 方案尚未覆盖 Redis 扣减后进程立即崩溃的极端窗口。
- 支付为模拟流程。
- 完整 Maven 测试可能因内部镜像 DNS 无法下载 Surefire 依赖。
- AI 外部模型不可用时使用本地规则降级，评测需要区分真实模型和降级结果。
