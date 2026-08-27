package com.seckill.constant;

/** Redis key contract for the coupon seckill transaction path. */
public final class SeckillRedisKeys {
    public static final String PENDING_ORDER_INDEX = "seckill:pending:orders";

    private SeckillRedisKeys() {
    }

    public static String activity(Long couponId) {
        return "seckill:activity:" + couponId;
    }

    public static String stock(Long couponId) {
        return "seckill:stock:" + couponId;
    }

    public static String users(Long couponId) {
        return "seckill:users:" + couponId;
    }

    public static String userCount(Long couponId) {
        return "seckill:user-count:" + couponId;
    }

    public static String pending(Long couponId) {
        return "seckill:pending:" + couponId;
    }

    public static String pendingOrder(String orderNo) {
        return "seckill:pending:order:" + orderNo;
    }
}
