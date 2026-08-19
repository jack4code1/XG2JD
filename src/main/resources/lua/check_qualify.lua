-- check_qualify.lua (Redis 8.x 兼容版)
-- KEYS[1]: seckill:coupon:{couponId}
-- KEYS[2]: seckill:user:{couponId}
-- ARGV[1]: userId
-- ARGV[2]: currentTime (毫秒时间戳字符串, 同位数可字符串比较)
-- ARGV[3]: perUserMax
-- 返回: -1=不在活动时间, -2=已抢过, -3=库存不足, 1=有资格

local couponKey = KEYS[1]
local userSetKey = KEYS[2]
-- RedisTemplate 的 JSON 序列化器会给字符串参数加引号，先还原为原始值。
local userId = string.gsub(ARGV[1], '^"(.*)"$', '%1')
local currentTime = string.gsub(ARGV[2], '^"(.*)"$', '%1')

-- 1. 活动时间校验（等长字符串比较等价于数值比较）
local startTime = redis.call('HGET', couponKey, 'start_time')
local endTime = redis.call('HGET', couponKey, 'end_time')
if startTime == false or endTime == false then
    return -1
end
if currentTime < startTime or currentTime > endTime then
    return -1
end

-- 2. 一人一单校验
if redis.call('SISMEMBER', userSetKey, userId) == 1 then
    return -2
end

-- 3. 库存预检（HINCRBY 0 读值，返回 Redis Integer）
local remain = redis.call('HINCRBY', couponKey, 'remain', 0)
if remain <= 0 then
    return -3
end

return 1
