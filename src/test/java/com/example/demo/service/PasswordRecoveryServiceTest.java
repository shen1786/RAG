package com.example.demo.service;

import com.example.demo.exception.BusinessException;
import com.example.demo.model.dto.auth.ForgotPasswordConfirmRequest;
import com.example.demo.model.dto.auth.PasswordResetCodeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordRecoveryServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private AuthAccountService authAccountService;

    @Mock
    private EmailService emailService;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private PasswordRecoveryService passwordRecoveryService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(passwordRecoveryService, "resetCodeTtlSeconds", 600L);
        ReflectionTestUtils.setField(passwordRecoveryService, "requestCooldownSeconds", 60L);
        ReflectionTestUtils.setField(passwordRecoveryService, "exposeResetCode", true);
    }

    @Test
    void createResetCode_success_storesCodeInRedis() {
        when(authAccountService.normalizeUsername("alice")).thenReturn("alice");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("auth:password:reset:cooldown:alice"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        PasswordResetCodeResponse response = passwordRecoveryService.createResetCode("alice");

        assertNotNull(response);
        assertNotNull(response.getResetCode());
        assertEquals(600L, response.getExpiresInSeconds());

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("auth:password:reset:code:alice"), codeCaptor.capture(), eq(Duration.ofSeconds(600L)));
        assertNotNull(codeCaptor.getValue());
    }

    @Test
    void createResetCode_cooldown_throwsBusinessException429() {
        when(authAccountService.normalizeUsername("alice")).thenReturn("alice");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("auth:password:reset:cooldown:alice"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> passwordRecoveryService.createResetCode("alice"));

        assertEquals(429, ex.getCode());
    }

    @Test
    void confirmReset_success_updatesPasswordAndCleansUp() {
        String rawCode = "123456";
        String encodedHash = passwordEncoder.encode(rawCode);

        when(authAccountService.normalizeUsername("alice")).thenReturn("alice");
        when(stringRedisTemplate.hasKey("auth:password:reset:lockout:alice:127.0.0.1")).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:password:reset:code:alice")).thenReturn(encodedHash);
        when(authAccountService.resetPasswordByUsername("alice", "newPass123", "newPass123")).thenReturn("user-1");

        ForgotPasswordConfirmRequest request = new ForgotPasswordConfirmRequest();
        request.setUsername("alice");
        request.setResetCode(rawCode);
        request.setNewPassword("newPass123");
        request.setConfirmNewPassword("newPass123");

        String userId = passwordRecoveryService.confirmReset(request, "127.0.0.1");

        assertEquals("user-1", userId);
        verify(authAccountService).resetPasswordByUsername("alice", "newPass123", "newPass123");
        verify(stringRedisTemplate).delete("auth:password:reset:code:alice");
        verify(stringRedisTemplate).delete("auth:password:reset:cooldown:alice");
    }

    @Test
    void confirmReset_invalidCode_throwsBusinessException400() {
        String storedHash = passwordEncoder.encode("123456");

        when(authAccountService.normalizeUsername("alice")).thenReturn("alice");
        when(stringRedisTemplate.hasKey("auth:password:reset:lockout:alice:127.0.0.1")).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:password:reset:code:alice")).thenReturn(storedHash);

        ForgotPasswordConfirmRequest request = new ForgotPasswordConfirmRequest();
        request.setUsername("alice");
        request.setResetCode("000000");
        request.setNewPassword("newPass123");
        request.setConfirmNewPassword("newPass123");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> passwordRecoveryService.confirmReset(request, "127.0.0.1"));

        assertEquals(400, ex.getCode());
        verify(valueOperations).increment("auth:password:reset:attempts:alice:127.0.0.1");
    }

    @Test
    void confirmReset_lockout_throwsBusinessException429() {
        when(authAccountService.normalizeUsername("alice")).thenReturn("alice");
        when(stringRedisTemplate.hasKey("auth:password:reset:lockout:alice:127.0.0.1")).thenReturn(true);

        ForgotPasswordConfirmRequest request = new ForgotPasswordConfirmRequest();
        request.setUsername("alice");
        request.setResetCode("123456");
        request.setNewPassword("newPass123");
        request.setConfirmNewPassword("newPass123");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> passwordRecoveryService.confirmReset(request, "127.0.0.1"));

        assertEquals(429, ex.getCode());
    }
}
