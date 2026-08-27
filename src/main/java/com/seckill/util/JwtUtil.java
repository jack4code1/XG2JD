package com.seckill.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类
 *
 * 面试可讲：
 * - Access Token 30min 无状态 + Refresh Token 7天 Redis存储
 * - jti (JWT ID) 用于登出黑名单定位
 * - 签名算法 HS256，SecretKey 从配置注入
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey secretKey;

    @Value("${seckill.jwt.access-expire-minutes:30}")
    private int accessExpireMinutes;

    public JwtUtil(@Value("${seckill.jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 Access Token (JWT)
     */
    public String generateAccessToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessExpireMinutes * 60_000L);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role != null ? role : "USER")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 验证并解析 JWT（不检查黑名单）
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 检查 Token 是否过期
     */
    public boolean isExpired(String token) {
        try {
            parseToken(token);
            return false;
        } catch (ExpiredJwtException e) {
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 获取 Token 剩余有效秒数
     */
    public long getRemainingTtl(String token) {
        try {
            Claims claims = parseToken(token);
            long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
            return Math.max(remaining / 1000, 0);
        } catch (Exception e) {
            return 0;
        }
    }
}
