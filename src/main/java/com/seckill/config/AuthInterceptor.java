package com.seckill.config;

import com.seckill.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 *
 * 面试可讲：
 * - preHandle: 从Header提取Token → 查黑名单 → 解析JWT → set ThreadLocal
 * - afterCompletion: 必须 UserContext.clear()，防止线程池串数据
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final com.seckill.util.JwtUtil jwtUtil;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

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

        // 放行商家列表（商城浏览）
        if (path.contains("/api/merchant/")) {
            return true;
        }

        // 放行健康检查
        if (path.contains("/actuator")) {
            return true;
        }

        String token = extractToken(request);
        if (token == null) {
            response.setStatus(401);
            return false;
        }

        try {
            // 1. 解析 JWT
            var claims = jwtUtil.parseToken(token);

            // 2. 检查黑名单
            String jti = claims.getId();
            Boolean blacklisted = redisTemplate.hasKey("jwt:blacklist:" + jti);
            if (Boolean.TRUE.equals(blacklisted)) {
                response.setStatus(401);
                return false;
            }

            // 3. 设置用户上下文
            Long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            UserContext.set(userId, username, role != null ? role : "USER");

            return true;

        } catch (Exception e) {
            response.setStatus(401);
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
}
