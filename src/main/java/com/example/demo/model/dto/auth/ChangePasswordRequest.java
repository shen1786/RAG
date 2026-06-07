package com.example.demo.model.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {
    @NotBlank(message = "当前密码不能为空")
    private String currentPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度必须在 6 到 64 个字符之间")
    private String newPassword;

    @NotBlank(message = "确认新密码不能为空")
    private String confirmNewPassword;
}
