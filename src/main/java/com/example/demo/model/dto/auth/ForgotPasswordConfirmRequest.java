package com.example.demo.model.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForgotPasswordConfirmRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度必须在 3 到 32 个字符之间")
    private String username;

    @NotBlank(message = "重置验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "重置验证码必须为 6 位数字")
    private String resetCode;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度必须在 6 到 64 个字符之间")
    private String newPassword;

    @NotBlank(message = "确认新密码不能为空")
    private String confirmNewPassword;
}
