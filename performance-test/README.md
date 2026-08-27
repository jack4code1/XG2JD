# Performance Test

This directory contains a reproducible, local-only JMeter setup for the seckill acceptance path. It measures the public HTTP request, including authentication, Redis eligibility checks, Lua stock deduction, and RabbitMQ publisher confirmation. It does not claim to measure Lua in isolation.

## Prerequisites

Start MySQL, Redis, and RabbitMQ, then start one application instance with the `perf` profile. The performance-only fixture API is unavailable without that profile. The following uses an isolated MySQL volume and host port `13306`, leaving any existing local MySQL data untouched.

```sh
docker compose -p seckill-perf -f docker-compose.yml -f performance-test/docker-compose.perf.yml up -d mysql
docker start seckill-redis seckill-rabbitmq
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:13306/seckill?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai'
export MYSQL_USER=root
export MYSQL_PASSWORD='local-password'
export RABBITMQ_USER=admin
export RABBITMQ_PASSWORD='local-password'
export JWT_SECRET='a-local-development-secret-with-at-least-32-bytes'
mvn spring-boot:run -Dspring-boot.run.profiles=perf
```

Set the existing local merchant password without committing it:

```sh
export PERF_MERCHANT_PASSWORD='your-local-merchant-password'
```

## Smoke Test

The smoke run creates one isolated shop, one active coupon with stock `1`, and one user. It sends one JMeter request, verifies the asynchronous order is eventually `CREATED`, and writes a sanitized summary.

```sh
./performance-test/scripts/run-smoke.sh
```

Use an explicit local URL or run ID when needed:

```sh
PERF_BASE_URL=http://127.0.0.1:8080 ./performance-test/scripts/run-smoke.sh smoke_20260827
```

The generated request CSV contains short-lived access tokens and is ignored by Git. The summary contains no credentials.

## Manual Flow

Prepare enough unique user/coupon pairs before starting JMeter. A user can successfully claim a coupon only once.

```sh
python3 performance-test/scripts/fixtures.py prepare \
  --run-id seckill_20260827 --users 200 --shops 2 --coupons 2 --stock 200

mkdir -p performance-test/results/seckill_20260827
jmeter -n -t performance-test/jmeter/seckill-smoke.jmx \
  -l performance-test/results/seckill_20260827/run.jtl \
  -JbaseUrl=http://127.0.0.1:8080 \
  -JdataFile=performance-test/data/seckill_20260827/seckill-requests.csv \
  -Jthreads=20 -JrampUp=5 -Jduration=60 \
  '-Jsample_variables=api_success,business_success,business_message,order_no,username,coupon_id'

python3 performance-test/scripts/fixtures.py verify \
  --run-id seckill_20260827 --jtl performance-test/results/seckill_20260827/run.jtl
python3 performance-test/scripts/fixtures.py summarize \
  --run-id seckill_20260827 --jtl performance-test/results/seckill_20260827/run.jtl
```

The JMeter plan accepts `baseUrl`, `token`, `threads`, `duration`, `rampUp`, `couponId`, and `deviceFingerprint`. Normal runs use `dataFile`, whose per-row values override the single-value token/coupon/device parameters.

`summary.json` includes total requests, API-success requests, business-success requests, business rejections, transport/API error rate, QPS, average, P50, P95, P99, and max latency. Business rejections such as sold-out or duplicate claims are reported separately and are not transport/API errors.

## Cleanup

Wait for `verify` to pass before cleanup. Cleanup only matches the exact `perf_seckill_*<run-id>*` fixture names, removes its tracked short-lived access sessions, and refuses to run while an order remains pending.

```sh
python3 performance-test/scripts/fixtures.py cleanup --run-id seckill_20260827
```

## Results Discipline

Run JMeter in CLI mode, keep the load generator separate from the application and middleware for formal results, run a short validation before each formal sample, repeat every level at least three times, and retain the sanitized summaries plus environment metadata. Do not write smoke-test numbers or a single-host result as a system capacity claim.
