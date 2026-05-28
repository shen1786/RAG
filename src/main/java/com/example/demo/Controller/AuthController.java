package com.example.demo.Controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckLogin;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.auth.AdminResetPasswordRequest;
import com.example.demo.model.dto.auth.ChangePasswordRequest;
import com.example.demo.model.dto.auth.ForgotPasswordConfirmRequest;
import com.example.demo.model.dto.auth.ForgotPasswordRequest;
import com.example.demo.model.dto.auth.LoginRequest;
import com.example.demo.model.dto.auth.LoginResponse;
import com.example.demo.model.dto.auth.PasswordResetCodeResponse;
import com.example.demo.model.dto.auth.RegisterRequest;
import com.example.demo.model.dto.auth.UserInfoResponse;
import com.example.demo.service.AuthApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthApplicationService authApplicationService;

    @SaIgnore
    @PostMapping("/register")
    public ApiResponse<UserInfoResponse> register(@RequestBody RegisterRequest request) {
        return authApplicationService.register(request);
    }

    @SaIgnore
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        return authApplicationService.login(request);
    }

    @SaCheckLogin
    @PostMapping("/logout")
    public ApiResponse<String> logout() {
        return authApplicationService.logout();
    }

    @SaCheckLogin
    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> me() {
        return authApplicationService.getCurrentUserInfo();
    }

    @SaCheckLogin
    @SaCheckPermission("auth:password:change")
    @PostMapping("/password/change")
    public ApiResponse<String> changePassword(@RequestBody ChangePasswordRequest request) {
        return authApplicationService.changePassword(request);
    }

    @SaCheckLogin
    @SaCheckPermission("user:password:reset")
    @PostMapping("/password/reset")
    public ApiResponse<String> resetPassword(@RequestBody AdminResetPasswordRequest request) {
        return authApplicationService.adminResetPassword(request);
    }

    @SaIgnore
    @PostMapping("/password/forgot/request")
    public ApiResponse<PasswordResetCodeResponse> requestForgotPasswordCode(@RequestBody ForgotPasswordRequest request) {
        return authApplicationService.requestPasswordResetCode(request);
    }

    @SaIgnore
    @PostMapping("/password/forgot/confirm")
    public ApiResponse<String> confirmForgotPassword(@RequestBody ForgotPasswordConfirmRequest request) {
        return authApplicationService.confirmForgotPassword(request);
    }
}
