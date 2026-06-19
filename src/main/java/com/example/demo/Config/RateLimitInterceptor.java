package com.example.demo.Config;

import com.example.demo.model.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

/**
 * 基于 Redis 的 IP 级速率限制拦截器。
 * <p>
 * 使用固定窗口计数器，对敏感端点（登录、注册、密码重置）进行防暴力破解保护。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 每个窗口最大请求数 */
    private final int maxRequests;

    /** 窗口时长（秒） */
    private final int windowSeconds;

    @Autowired
    public RateLimitInterceptor(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, 10, 60);
    }

    public RateLimitInterceptor(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                int maxRequests, int windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String clientIp = resolveClientIp(request);
        String key = "rate_limit:" + request.getRequestURI() + ":" + clientIp;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }

        if (count != null && count > maxRequests) {
            log.warn("速率限制触发: IP={}, URI={}, count={}", clientIp, request.getRequestURI(), count);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            ApiResponse<?> body = ApiResponse.error("请求过于频繁，请稍后再试");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return false;
        }

        return true;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 取第一个 IP（客户端真实 IP）
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
