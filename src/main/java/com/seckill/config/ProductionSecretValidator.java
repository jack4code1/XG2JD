package com.seckill.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Refuse known demo credentials when the production profile is enabled. */
@Component
@RequiredArgsConstructor
public class ProductionSecretValidator {
    private final Environment environment;

    @PostConstruct
    void validate() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;
        requireSecret("seckill.jwt.secret", "your-jwt-secret-minimum-32-bytes-long");
        requireSecret("spring.ai.openai.api-key", "your-deepseek-api-key");
        requireSecret("spring.datasource.password", "root123");
        requireSecret("spring.rabbitmq.password", "admin123");
    }

    private void requireSecret(String name, String unsafeDefault) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || unsafeDefault.equals(value)) {
            throw new IllegalStateException("生产环境必须配置安全的 " + name);
        }
    }
}
