package com.example.demo.model.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForgotPasswordConfirmRequest {
    private String username;
    private String resetCode;
    private String newPassword;
    private String confirmNewPassword;
}
