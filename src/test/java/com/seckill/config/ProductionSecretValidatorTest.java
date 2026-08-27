package com.seckill.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionSecretValidatorTest {
    @Test
    void productionRefusesMissingRequiredSecret() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(environment.getProperty(any(String.class))).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> new ProductionSecretValidator(environment).validate());
    }
}
