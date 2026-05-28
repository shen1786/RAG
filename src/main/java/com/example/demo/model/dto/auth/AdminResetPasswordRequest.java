package com.example.demo.model.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminResetPasswordRequest {
    private String username;
    private String newPassword;
    private String confirmNewPassword;
}
