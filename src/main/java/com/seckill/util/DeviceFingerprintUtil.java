package com.seckill.util;

/**
 * 设备指纹工具类
 *
 * 面试可讲：
 * - 采集 Canvas Fingerprint + WebGL + 屏幕信息 → SHA256
 * - 不依赖 Cookie/LocalStorage，清缓存无法绕过
 * - 生产环境建议用专业 SDK（如数美、网易易盾）
 */
public class DeviceFingerprintUtil {

    private static final ThreadLocal<String> CURRENT_FINGERPRINT = new ThreadLocal<>();

    private DeviceFingerprintUtil() {}

    /**
     * 由拦截器/Controller 在请求入口设置
     */
    public static void setCurrentDeviceFingerprint(String fingerprint) {
        CURRENT_FINGERPRINT.set(fingerprint);
    }

    /**
     * 获取当前请求的设备指纹
     */
    public static String getCurrentDeviceFingerprint() {
        return CURRENT_FINGERPRINT.get();
    }

    /**
     * 请求结束后清理（必须！）
     */
    public static void clear() {
        CURRENT_FINGERPRINT.remove();
    }
}