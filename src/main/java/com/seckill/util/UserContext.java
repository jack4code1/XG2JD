package com.seckill.util;

public class UserContext {
    private static final ThreadLocal<UserInfo> HOLDER = new ThreadLocal<>();
    private UserContext() {}

    public static void set(Long userId, String username, String role) {
        HOLDER.set(new UserInfo(userId, username, role));
    }
    public static Long getUserId() { UserInfo i = HOLDER.get(); return i != null ? i.userId : null; }
    public static String getUsername() { UserInfo i = HOLDER.get(); return i != null ? i.username : null; }
    public static String getRole() { UserInfo i = HOLDER.get(); return i != null ? i.role : null; }
    public static void clear() { HOLDER.remove(); }
    public record UserInfo(Long userId, String username, String role) {}
}