package com.example.demo.service;

import com.example.demo.exception.BusinessException;
import com.example.demo.model.dto.auth.ForgotPasswordConfirmRequest;
import com.example.demo.model.dto.auth.PasswordResetCodeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.Duration;

@Slf4j
@Service
public class PasswordRecoveryService {

    private static final String RESET_CODE_PREFIX = "auth:password:reset:code:";
    private static final String RESET_COOLDOWN_PREFIX = "auth:password:reset:cooldown:";
    private static final String RESET_ATTEMPTS_PREFIX = "auth:password:reset:attempts:";
    private static final String RESET_LOCKOUT_PREFIX = "auth:password:reset:lockout:";
    private static final String DIGITS = "0123456789";

    /** 确认接口最大失败尝试次数 */
    private static final int MAX_CONFIRM_ATTEMPTS = 5;
    /** 失败计数窗口（秒），与 resetCodeTtl 一致 */
    private static final long ATTEMPTS_WINDOW_SECONDS = 600;
    /** 超限锁定时长（秒） */
    private static final long LOCKOUT_DURATION_SECONDS = 1800;

    private final StringRedisTemplate stringRedisTemplate;
    private final AuthAccountService authAccountService;
    private final EmailService emailService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${auth.password-recovery.reset-code-ttl-seconds:600}")
    private long resetCodeTtlSeconds;

    @Value("${auth.password-recovery.request-cooldown-seconds:60}")
    private long requestCooldownSeconds;

    @Value("${auth.password-recovery.expose-reset-code:false}")
    private boolean exposeResetCode;

    public PasswordRecoveryService(StringRedisTemplate stringRedisTemplate,
                                   AuthAccountService authAccountService,
                                   EmailService emailService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.authAccountService = authAccountService;
        this.emailService = emailService;
    }

    public PasswordResetCodeResponse createResetCode(String username) {
        String normalizedUsername = authAccountService.normalizeUsername(username);
        String cooldownKey = RESET_COOLDOWN_PREFIX + normalizedUsername;
        Boolean accepted = stringRedisTemplate.opsForValue().setIfAbsent(
                cooldownKey,
                "1",
                Duration.ofSeconds(requestCooldownSeconds)
        );
        if (!Boolean.TRUE.equals(accepted)) {
            throw new BusinessException(429, "重置密码请求过于频繁，请稍后再试");
        }

        String resetCode = generateResetCode();
        String maskedCode = resetCode.substring(0, 2) + "****";
        log.info("[密码重置模拟] 已为用户名 '{}' 生成重置验证码: {}", normalizedUsername, maskedCode);
        
        // Dispatch to EmailService (which attempts real mail sending, and falls back to simulated email files)
        String targetEmail = normalizedUsername.contains("@") ? normalizedUsername : normalizedUsername + "@example.com";
        emailService.sendResetCode(normalizedUsername, targetEmail, resetCode, resetCodeTtlSeconds);

        String codeKey = RESET_CODE_PREFIX + normalizedUsername;
        stringRedisTemplate.opsForValue().set(
                codeKey,
                passwordEncoder.encode(resetCode),
                Duration.ofSeconds(resetCodeTtlSeconds)
        );

        return new PasswordResetCodeResponse(
                exposeResetCode ? resetCode : null,
                resetCodeTtlSeconds
        );
    }

    public String confirmReset(ForgotPasswordConfirmRequest request, String clientIp) {
        String normalizedUsername = authAccountService.normalizeUsername(request.getUsername());
        String code = request.getResetCode();
        if (code == null || code.isBlank()) {
            throw new BusinessException(400, "重置码不能为空");
        }

        // 检查是否被锁定（IP + username 维度）
        String lockoutKey = RESET_LOCKOUT_PREFIX + normalizedUsername + ":" + clientIp;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockoutKey))) {
            throw new BusinessException(429, "验证失败次数过多，请30分钟后再试");
        }

        String codeKey = RESET_CODE_PREFIX + normalizedUsername;
        String storedHash = stringRedisTemplate.opsForValue().get(codeKey);
        if (storedHash == null || !passwordEncoder.matches(code.trim(), storedHash)) {
            // 记录失败尝试
            recordFailedAttempt(normalizedUsername, clientIp);
            throw new BusinessException(400, "重置码无效或已过期");
        }

        // 验证成功，清除失败计数
        clearFailedAttempts(normalizedUsername, clientIp);

        String userId = authAccountService.resetPasswordByUsername(
                normalizedUsername,
                request.getNewPassword(),
                request.getConfirmNewPassword()
        );
        stringRedisTemplate.delete(codeKey);
        stringRedisTemplate.delete(RESET_COOLDOWN_PREFIX + normalizedUsername);
        return userId;
    }

    private void recordFailedAttempt(String username, String clientIp) {
        String attemptsKey = RESET_ATTEMPTS_PREFIX + username + ":" + clientIp;
        Long count = stringRedisTemplate.opsForValue().increment(attemptsKey);
        if (count != null && count == 1) {
            stringRedisTemplate.expire(attemptsKey, Duration.ofSeconds(ATTEMPTS_WINDOW_SECONDS));
        }
        if (count != null && count >= MAX_CONFIRM_ATTEMPTS) {
            String lockoutKey = RESET_LOCKOUT_PREFIX + username + ":" + clientIp;
            stringRedisTemplate.opsForValue().set(lockoutKey, "1", Duration.ofSeconds(LOCKOUT_DURATION_SECONDS));
            stringRedisTemplate.delete(attemptsKey);
            log.warn("密码重置确认接口锁定: username={}, ip={}, attempts={}", username, clientIp, count);
        }
    }

    private void clearFailedAttempts(String username, String clientIp) {
        stringRedisTemplate.delete(RESET_ATTEMPTS_PREFIX + username + ":" + clientIp);
        stringRedisTemplate.delete(RESET_LOCKOUT_PREFIX + username + ":" + clientIp);
    }

    private String generateResetCode() {
        StringBuilder builder = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            builder.append(DIGITS.charAt(secureRandom.nextInt(DIGITS.length())));
        }
        return builder.toString();
    }
}
