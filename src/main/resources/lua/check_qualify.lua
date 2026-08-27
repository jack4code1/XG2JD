-- check_qualify.lua (Redis 8.x 兼容版)
-- KEYS[1]: seckill:activity:{couponId} (Hash: status/start_time/end_time/per_user_max)
-- KEYS[2]: seckill:stock:{couponId} (String)
-- KEYS[3]: seckill:users:{couponId} (Set)
-- KEYS[4]: seckill:user-count:{couponId} (Hash, only for per_user_max > 1)
-- KEYS[5]: seckill:pending:{couponId} (List)
-- KEYS[6]: seckill:pending:orders (ZSET, retry schedule)
-- ARGV[1]: userId
-- ARGV[2]: currentTime (毫秒时间戳字符串, 同位数可字符串比较)
-- ARGV[3]: orderNo
-- ARGV[4]: couponId
-- ARGV[5]: userWeight
-- ARGV[6]: messageTimestamp
-- 返回: -1=未开始, -2=已结束, -3=暂停, -4=库存不足, -5=重复领取, -6=活动状态缺失, >=0=剩余库存

local activityKey = KEYS[1]
local stockKey = KEYS[2]
local userSetKey = KEYS[3]
local userCountKey = KEYS[4]
local pendingActivityKey = KEYS[5]
local pendingIndexKey = KEYS[6]
-- RedisTemplate 的 JSON 序列化器会给字符串参数加引号，先还原为原始值。
local userId = string.gsub(ARGV[1], '^"(.*)"$', '%1')
local currentTime = string.gsub(ARGV[2], '^"(.*)"$', '%1')
local orderNo = string.gsub(ARGV[3], '^"(.*)"$', '%1')
local couponId = string.gsub(ARGV[4], '^"(.*)"$', '%1')
local userWeight = string.gsub(ARGV[5], '^"(.*)"$', '%1')
local messageTimestamp = string.gsub(ARGV[6], '^"(.*)"$', '%1')

-- 1. 活动状态和时间窗口必须在扣库存前完成校验。
local status = redis.call('HGET', activityKey, 'status')
local startTime = redis.call('HGET', activityKey, 'start_time')
local endTime = redis.call('HGET', activityKey, 'end_time')
local perUserMax = redis.call('HGET', activityKey, 'per_user_max')
if status == false or startTime == false or endTime == false or perUserMax == false then return -6 end
if status == '0' then return -1 end
if status == '2' then return -2 end
if status == '3' then return -3 end
if status ~= '1' then return -6 end
if tonumber(currentTime) < tonumber(startTime) then return -1 end
if tonumber(currentTime) > tonumber(endTime) then return -2 end

-- 2. One-per-user uses Set membership. Campaigns explicitly configured above
-- one use a per-user counter while retaining the Set as the claimant index.
if tonumber(perUserMax) <= 1 then
    if redis.call('SISMEMBER', userSetKey, userId) == 1 then return -5 end
elseif tonumber(redis.call('HGET', userCountKey, userId) or '0') >= tonumber(perUserMax) then
    return -5
end

-- 3. Stock read, decrement and user marker are one Redis transaction.
local remain = tonumber(redis.call('GET', stockKey) or '-1')
if remain < 0 then return -6 end
if remain <= 0 then return -4 end

-- 校验、扣库存、标记用户必须在同一个 Lua 执行单元内完成，避免并发重复领取。
redis.call('DECR', stockKey)
redis.call('SADD', userSetKey, userId)
if tonumber(perUserMax) > 1 then redis.call('HINCRBY', userCountKey, userId, 1) end

-- Persist the order message in Redis in the same atomic unit as stock
-- deduction. If this process dies before publishing to RabbitMQ, the recovery
-- scheduler will replay this pending message. Duplicate delivery is safe
-- because consumers use orderNo for idempotency.
local pendingOrderKey = 'seckill:pending:order:' .. orderNo
redis.call('HSET', pendingOrderKey,
    'order_no', orderNo,
    'user_id', userId,
    'coupon_id', couponId,
    'user_weight', userWeight,
    'timestamp', messageTimestamp,
    'retry_count', '0',
    'state', 'PENDING')
redis.call('EXPIRE', pendingOrderKey, 86400)
redis.call('RPUSH', pendingActivityKey, orderNo)
redis.call('EXPIRE', pendingActivityKey, 86400)
redis.call('ZADD', pendingIndexKey, currentTime, orderNo)

return remain - 1
