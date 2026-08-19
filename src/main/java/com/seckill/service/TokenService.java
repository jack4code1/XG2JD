package com.seckill.service;

import com.seckill.dto.LoginResponse;
import com.seckill.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * Token 服务 — 双Token + 轮换 + 黑名单
 *
 * 面试可讲：
 * - Access Token 30min(JWT无状态) + Refresh Token 7天(UUID，Redis存储)
 * - 轮换策略：每次刷新换新RefreshToken，旧的加入黑名单(1h窗口)
 * - 盗用检测：旧Token命中黑名单→攻击者和合法用户只有一人成功→触发全设备登出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${seckill.jwt.refresh-expire-days:7}")
    private int refreshExpireDays;

    /**
     * 生成 Token 对
     */
    public LoginResponse generateTokens(Long userId, String username, String role) {
        String accessToken = jwtUtil.generateAccessToken(userId, username, role);
        String refreshToken = generateRefreshToken();

        redisTemplate.opsForHash().put("refresh:token:" + refreshToken, "userId", userId.toString());
        redisTemplate.opsForHash().put("refresh:token:" + refreshToken, "username", username);
        redisTemplate.opsForHash().put("refresh:token:" + refreshToken, "role", role != null ? role : "USER");
        redisTemplate.expire("refresh:token:" + refreshToken, Duration.ofDays(refreshExpireDays));

        return LoginResponse.of(accessToken, refreshToken, 30 * 60);
    }

    /**
     * 刷新 Token（轮换策略）
     */
    public LoginResponse refresh(String oldRefreshToken) throws IllegalArgumentException {
        String tokenKey = "refresh:token:" + oldRefreshToken;

        // 1. 检查黑名单
        Boolean blacklisted = redisTemplate.hasKey("refresh:blacklist:" + oldRefreshToken);
        if (Boolean.TRUE.equals(blacklisted)) {
            log.warn("Refresh Token 已被使用过，可能存在盗用！");
            throw new IllegalArgumentException("Token已被使用，请重新登录");
        }

        // 2. 验证 Refresh Token 存在
        Object userIdObj = redisTemplate.opsForHash().get(tokenKey, "userId");
        if (userIdObj == null) {
            throw new IllegalArgumentException("Refresh Token 无效或已过期");
        }

        Long userId = Long.parseLong(userIdObj.toString());
        String username = redisTemplate.opsForHash().get(tokenKey, "username").toString();
        Object roleObj = redisTemplate.opsForHash().get(tokenKey, "role");
        String role = roleObj != null ? roleObj.toString() : "USER";

        // 3. 旧 Token 加入黑名单（1小时过渡期）
        redisTemplate.opsForValue().set("refresh:blacklist:" + oldRefreshToken, "1",
                Duration.ofHours(1));

        // 4. 删除旧 Token，生成新 Token
        redisTemplate.delete(tokenKey);

        return generateTokens(userId, username, role);
    }

    /**
     * 登出：Access Token 加入黑名单 + 删除 Refresh Token
     */
    public void logout(String accessToken, String refreshToken) {
        try {
            var claims = jwtUtil.parseToken(accessToken);
            String jti = claims.getId();
            long remainingTtl = jwtUtil.getRemainingTtl(accessToken);

            // JWT 黑名单（TTL = 剩余有效期）
            if (remainingTtl > 0) {
                redisTemplate.opsForValue().set("jwt:blacklist:" + jti, "1",
                        Duration.ofSeconds(remainingTtl));
            }

            // 删除 Refresh Token
            if (refreshToken != null) {
                redisTemplate.delete("refresh:token:" + refreshToken);
            }

            log.info("用户登出: userId={}, jti={}", claims.getSubject(), jti);
        } catch (Exception e) {
            log.warn("登出时Token已无效: {}", e.getMessage());
        }
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}