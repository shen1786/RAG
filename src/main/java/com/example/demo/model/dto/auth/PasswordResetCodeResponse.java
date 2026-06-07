package com.example.demo.model.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetCodeResponse {
    /**
     * 仅在开发调试时（expose-reset-code=true）才填充，生产环境为 null，
     * Jackson 默认不序列化 null 字段，避免重置码泄露到 API 响应。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String resetCode;
    private long expiresInSeconds;
}
