package com.seckill.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoginRequestValidationTest {
    @Test
    void rejectsBlankCredentialsAndInvalidRole() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        LoginRequest request = LoginRequest.builder().username(" ").password("123").role("ADMIN").build();

        assertEquals(4, validator.validate(request).size());
    }
}
