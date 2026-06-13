package com.example.demo.service.retrieval;

import org.springframework.stereotype.Component;

/**
 * Redis VectorStore 用户过滤表达式构建器。
 */
@Component
public class UserFilterBuilder {

    /**
     * 构建 user_id 过滤表达式，含 Redis Tag 特殊字符转义。
     */
    public String build(String userId) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        return "user_id == '" + escapeRedisTagValue(userId) + "'";
    }

    private String escapeRedisTagValue(String rawValue) {
        StringBuilder escaped = new StringBuilder(rawValue.length());
        for (int i = 0; i < rawValue.length(); i++) {
            char current = rawValue.charAt(i);
            if (Character.isLetterOrDigit(current) || current == '_') {
                escaped.append(current);
                continue;
            }
            escaped.append('\\').append(current);
        }
        return escaped.toString();
    }
}
