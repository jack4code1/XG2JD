package com.seckill.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.ResultCode;
import com.seckill.dto.LoginResponse;
import com.seckill.interceptor.AuthInterceptor;
import com.seckill.service.TokenService;
import com.seckill.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class AuthInterceptorTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginSessionCanBeRestoredFromTheSameRedisHashKey() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        Map<String, Map<Object, Object>> sessions = new HashMap<>();
        when(redis.opsForHash()).thenReturn(hashes);
        doAnswer(invocation -> {
            sessions.put(invocation.getArgument(0), new HashMap<>(invocation.getArgument(1)));
            return null;
        }).when(hashes).putAll(anyString(), anyMap());
        when(hashes.entries(anyString())).thenAnswer(invocation ->
                sessions.getOrDefault(invocation.getArgument(0), Map.of()));

        TokenService tokenService = new TokenService(redis);
        ReflectionTestUtils.setField(tokenService, "accessTokenTtlMinutes", 30);
        LoginResponse login = tokenService.generateTokens(42L, "alice", "USER");

        AuthInterceptor interceptor = interceptor(redis);
        MockHttpServletRequest request = requestWithToken(login.getAccessToken());

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(42L, UserContext.getUserId());
        assertEquals("alice", UserContext.getUsername());
        assertEquals("USER", UserContext.getRole());

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redis, org.mockito.Mockito.times(2)).expire(
                org.mockito.ArgumentMatchers.eq("login:token:" + login.getAccessToken()), ttl.capture());
        assertEquals(Duration.ofMinutes(30), ttl.getValue());
        verify(hashes).putAll("login:token:" + login.getAccessToken(), Map.of(
                "userId", "42", "username", "alice", "role", "USER"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void absentRedisSessionReturnsBusinessUnauthorizedResponse() throws Exception {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(anyString())).thenReturn(Map.of());
        AuthInterceptor interceptor = interceptor(redis);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(requestWithToken("expired-token"), response, new Object()));
        assertEquals(200, response.getStatus());
        assertEquals(ResultCode.UNAUTHORIZED.getCode(),
                new ObjectMapper().readTree(response.getContentAsString()).get("code").asInt());
    }

    private AuthInterceptor interceptor(RedisTemplate<String, Object> redis) {
        AuthInterceptor interceptor = new AuthInterceptor(redis, new ObjectMapper());
        ReflectionTestUtils.setField(interceptor, "accessTokenTtlMinutes", 30);
        return interceptor;
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/coupon/1");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
