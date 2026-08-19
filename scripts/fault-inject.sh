#!/bin/bash
# 秒杀系统故障注入脚本
# 用法: ./fault-inject.sh <场景> [inject|recover]

set -e

log() { echo "[$(date '+%H:%M:%S')] $1"; }

# ──── 场景1: Redis 宕机 ────
redis_down() {
    log "注入: Redis 宕机"
    docker pause seckill-redis 2>/dev/null || docker stop seckill-redis
}

redis_recover() {
    log "恢复: Redis"
    docker unpause seckill-redis 2>/dev/null || docker start seckill-redis
    sleep 3
    log "Redis 状态: $(docker ps --filter name=seckill-redis --format '{{.Status}}')"
}

# ──── 场景2: MQ 积压 ────
mq_backlog() {
    log "注入: MQ 积压(暂停消费者)"
    docker pause seckill-rabbitmq
}

mq_recover() {
    log "恢复: MQ"
    docker unpause seckill-rabbitmq
    sleep 2
    log "MQ 状态: $(docker ps --filter name=seckill-rabbitmq --format '{{.Status}}')"
}

# ──── 场景3: DB 慢查询 ────
db_slow() {
    log "注入: DB 慢查询"
    docker exec seckill-mysql mysql -uroot -proot123 seckill \
        -e "SELECT SLEEP(5)" &
    log "慢查询已提交 (PID=$!)"
}

db_recover() {
    log "DB 慢查询自动恢复(SLEEP结束后释放连接)"
}

# ──── 场景4: 网络延迟 ────
network_delay() {
    log "注入: 网络延迟 200ms"
    # macOS 使用 pfctl + dummynet, Linux 使用 tc
    if [[ "$(uname)" == "Darwin" ]]; then
        log "macOS: 跳过 tc，用 docker network 模拟"
        docker network disconnect seckill_default seckill-redis 2>/dev/null || true
        sleep 2
        docker network connect seckill_default seckill-redis
    else
        sudo tc qdisc add dev eth0 root netem delay 200ms 2>/dev/null || \
            log "tc 不可用，跳过网络延迟注入"
    fi
}

network_recover() {
    if [[ "$(uname)" != "Darwin" ]]; then
        sudo tc qdisc del dev eth0 root 2>/dev/null || true
    fi
    log "网络恢复"
}

# ──── 场景5: CPU 压力 ────
cpu_stress() {
    log "注入: CPU 压力"
    if command -v stress &>/dev/null; then
        stress --cpu 2 --timeout 30 &
    else
        log "stress 未安装，跳过"
    fi
}

cpu_recover() {
    log "CPU 压力自动恢复"
}

# ──── 主入口 ────
case "${1:-}" in
    redis)    [[ "${2:-inject}" == "recover" ]] && redis_recover || redis_down ;;
    mq)       [[ "${2:-inject}" == "recover" ]] && mq_recover || mq_backlog ;;
    db)       [[ "${2:-inject}" == "recover" ]] && db_recover || db_slow ;;
    network)  [[ "${2:-inject}" == "recover" ]] && network_recover || network_delay ;;
    cpu)      [[ "${2:-inject}" == "recover" ]] && cpu_recover || cpu_stress ;;
    *)
        echo "用法: $0 <redis|mq|db|network|cpu> [inject|recover]"
        echo ""
        echo "场景说明:"
        echo "  redis   - Redis宕机 → 预期: Caffeine L1兜底+Sentinel限流降级"
        echo "  mq      - MQ积压   → 预期: 死信队列兜底+积压告警"
        echo "  db      - DB慢查询 → 预期: Sentinel熔断+降级返回'活动火爆'"
        echo "  network - 网络延迟 → 预期: 超时重试+连接池保护"
        echo "  cpu     - CPU压力  → 预期: 限流触发+优雅降级"
        ;;
esac