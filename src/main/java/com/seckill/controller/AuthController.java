package com.seckill.controller;

import com.seckill.dto.LoginRequest;
import com.seckill.dto.LoginResponse;
import com.seckill.dto.RefreshRequest;
import com.seckill.model.User;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.UserRepository;
import com.seckill.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 认证接口
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 注册
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody LoginRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            return Map.of("success", false, "message", "用户名已存在");
        }

        String role = request.getRole() != null ? request.getRole().toUpperCase() : "USER";

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .build();
        userRepository.save(user);

        // 商家注册时自动创建店铺
        if ("MERCHANT".equals(role)) {
            com.seckill.model.Merchant merchant = com.seckill.model.Merchant.builder()
                    .userId(user.getId())
                    .shopName(request.getUsername() + "的店铺")
                    .category("其他")
                    .build();
            merchantRepository.save(merchant);
        }

        return Map.of("success", true, "message", "注册成功", "userId", user.getId(), "role", role);
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 更新最后登录时间
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        return tokenService.generateTokens(user.getId(), user.getUsername(), user.getRole());
    }

    /**
     * 刷新 Token
     */
    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody RefreshRequest request) {
        return tokenService.refresh(request.getRefreshToken());
    }

    /**
     * 登出
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader("Authorization") String authHeader,
                                       @RequestBody(required = false) Map<String, String> body) {
        String accessToken = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring(7) : null;
        String refreshToken = body != null ? body.get("refreshToken") : null;
        tokenService.logout(accessToken, refreshToken);
        return Map.of("success", true, "message", "已登出");
    }
}