package com.seckill.util;

/**
 * ThreadLocal 用户上下文
 *
 * 面试可讲：
 * - 拦截器 set → 业务代码 get → finally remove()
 * - 必须 remove() 否则线程池复用线程时串数据
 */
public class UserContext {

    private static final ThreadLocal<UserInfo> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    public static void set(Long userId, String username) {
        HOLDER.set(new UserInfo(userId, username));
    }

    public static Long getUserId() {
        UserInfo info = HOLDER.get();
        return info != null ? info.userId : null;
    }

    public static String getUsername() {
        UserInfo info = HOLDER.get();
        return info != null ? info.username : null;
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record UserInfo(Long userId, String username) {}
}