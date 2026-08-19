-- deduct_stock.lua (Redis 8.x 兼容版)
-- KEYS[1]: seckill:coupon:{couponId}
-- ARGV[1]: 预期version (不使用，保留兼容)
-- 返回: -1=库存不足, >=0=扣减成功(返回剩余库存)

local couponKey = KEYS[1]

-- 读库存（HINCRBY 0 返回数字）
local remain = redis.call('HINCRBY', couponKey, 'remain', 0)
if remain <= 0 then
    return -1
end

-- 原子扣减
redis.call('HINCRBY', couponKey, 'remain', -1)
redis.call('HINCRBY', couponKey, 'version', 1)

return remain - 1