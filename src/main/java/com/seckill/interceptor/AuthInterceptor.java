package com.seckill.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.Result;
import com.seckill.common.ResultCode;
import com.seckill.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 *
 * 设计说明：
 * - preHandle: 从Header提取Token → Redis 查会话并续期 → set ThreadLocal
 * - afterCompletion: 必须 UserContext.clear()，防止线程池串数据
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${seckill.auth.access-token-ttl-minutes:30}")
    private int accessTokenTtlMinutes;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String path = request.getRequestURI();

        // 放行登录注册
        if (path.contains("/api/auth/")) {
            return true;
        }

        // 仅放行 AI 健康检查，策划和执行接口必须登录并由控制器校验商家角色。
        if (path.equals("/api/ai/health")) {
            return true;
        }

        // Only liveness and Prometheus scraping are anonymous. Other actuator
        // endpoints require a normal authenticated request.
        if (path.equals("/actuator/health") || path.equals("/actuator/prometheus")) {
            return true;
        }

        String token = extractToken(request);
        if (token == null) {
            writeUnauthorized(response);
            return false;
        }

        try {
            java.util.Map<Object, Object> session = redisTemplate.opsForHash().entries("login:token:" + token);
            if (session == null || session.isEmpty()) {
                writeUnauthorized(response);
                return false;
            }

            Long userId = Long.parseLong(String.valueOf(session.get("userId")));
            String username = String.valueOf(session.get("username"));
            Object role = session.get("role");
            // COSEC: Sliding Redis TTL makes session revocation and expiry
            // consistent across application instances without using HttpSession.
            redisTemplate.expire("login:token:" + token,
                    java.time.Duration.ofMinutes(accessTokenTtlMinutes));
            UserContext.set(userId, username, role == null ? "USER" : String.valueOf(role));
            return true;
        } catch (Exception e) {
            writeUnauthorized(response);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response) {
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            objectMapper.writeValue(response.getWriter(), Result.fail(ResultCode.UNAUTHORIZED));
        } catch (java.io.IOException ignored) {
            // The servlet container will finish the failed response.
        }
    }
}
