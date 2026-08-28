-- Atomic state transitions for a Redis-accepted order pending RabbitMQ delivery.
-- KEYS[1]: seckill:pending:order:{orderNo}
-- KEYS[2]: seckill:pending:orders
-- KEYS[3]: seckill:pending:{couponId}
-- ARGV[1]: action (CONFIRM_PUBLISHED / ACK / CLAIM_RECOVERY / RETRY_LATER / DEFER_PUBLISHED)
-- ARGV[2]: orderNo
-- ARGV[3]: nowMillis
-- ARGV[4]: nextDueMillis
-- ARGV[5]: maxRetries

local pendingKey = KEYS[1]
local pendingIndex = KEYS[2]
local pendingActivity = KEYS[3]
local action = ARGV[1]
local orderNo = ARGV[2]
local now = tonumber(ARGV[3])
local nextDue = tonumber(ARGV[4])
local maxRetries = tonumber(ARGV[5])

if action == 'ACK' then
    local existed = redis.call('EXISTS', pendingKey)
    redis.call('DEL', pendingKey)
    redis.call('ZREM', pendingIndex, orderNo)
    redis.call('LREM', pendingActivity, 1, orderNo)
    return existed
end

if redis.call('EXISTS', pendingKey) == 0 then
    return 0
end

local state = redis.call('HGET', pendingKey, 'state')
if state == false or state == 'FAILED' then
    return 0
end

if action == 'CONFIRM_PUBLISHED' then
    redis.call('HSET', pendingKey, 'state', 'PUBLISHED', 'published_at', tostring(now))
    redis.call('ZADD', pendingIndex, nextDue, orderNo)
    return 1
end

if action == 'CLAIM_RECOVERY' then
    local dueAt = redis.call('ZSCORE', pendingIndex, orderNo)
    if dueAt == false or tonumber(dueAt) > now then
        return 0
    end
    if state ~= 'PUBLISHING' and state ~= 'RETRY_WAIT' and state ~= 'RECOVERING' then
        return 0
    end
    redis.call('HSET', pendingKey, 'state', 'RECOVERING')
    redis.call('ZADD', pendingIndex, nextDue, orderNo)
    return 1
end

if action == 'RETRY_LATER' then
    if state ~= 'RECOVERING' then
        return 0
    end
    local retryCount = tonumber(redis.call('HGET', pendingKey, 'retry_count') or '0') + 1
    redis.call('HSET', pendingKey, 'retry_count', tostring(retryCount))
    if retryCount >= maxRetries then
        redis.call('HSET', pendingKey, 'state', 'FAILED')
        redis.call('ZREM', pendingIndex, orderNo)
        return -retryCount
    end
    redis.call('HSET', pendingKey, 'state', 'RETRY_WAIT')
    redis.call('ZADD', pendingIndex, nextDue, orderNo)
    return retryCount
end

if action == 'DEFER_PUBLISHED' and state == 'PUBLISHED' then
    redis.call('ZADD', pendingIndex, nextDue, orderNo)
    return 1
end

return 0
