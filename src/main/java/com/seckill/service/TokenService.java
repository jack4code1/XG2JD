package com.seckill.service;

import com.seckill.dto.LoginResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Base64;

/**
 * Redis-backed access-token sessions plus rotating refresh tokens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${seckill.jwt.refresh-expire-days:7}")
    private int refreshExpireDays;

    @Value("${seckill.auth.access-token-ttl-minutes:30}")
    private int accessTokenTtlMinutes;

    /**
     * 生成 Token 对
     */
    public LoginResponse generateTokens(Long userId, String username, String role) {
        String normalizedRole = role != null ? role : "USER";
        String accessToken = generateOpaqueToken();
        String refreshToken = generateRefreshToken();

        // COSEC: The opaque token is the only session credential. Redis holds
        // minimal identity data server-side so it can be invalidated centrally.
        redisTemplate.opsForHash().putAll(accessTokenKey(accessToken), Map.of(
                "userId", userId.toString(),
                "username", username,
                "role", normalizedRole));
        redisTemplate.expire(accessTokenKey(accessToken), Duration.ofMinutes(accessTokenTtlMinutes));

        redisTemplate.opsForHash().put("refresh:token:" + refreshToken, "userId", userId.toString());
        redisTemplate.opsForHash().put("refresh:token:" + refreshToken, "username", username);
        redisTemplate.opsForHash().put("refresh:token:" + refreshToken, "role", normalizedRole);
        redisTemplate.expire("refresh:token:" + refreshToken, Duration.ofDays(refreshExpireDays));

        return LoginResponse.of(accessToken, refreshToken, accessTokenTtlMinutes * 60L, normalizedRole);
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
     * 登出：删除 Redis Access Token 会话和 Refresh Token。
     */
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && !accessToken.isBlank()) {
            redisTemplate.delete(accessTokenKey(accessToken));
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            redisTemplate.delete("refresh:token:" + refreshToken);
        }
        log.info("用户登出，会话已删除");
    }

    private String generateRefreshToken() {
        return generateOpaqueToken();
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String accessTokenKey(String accessToken) {
        return "login:token:" + accessToken;
    }
}
