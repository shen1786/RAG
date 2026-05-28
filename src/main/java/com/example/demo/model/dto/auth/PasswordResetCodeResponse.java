package com.example.demo.model.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetCodeResponse {
    private String resetCode;
    private long expiresInSeconds;
}
