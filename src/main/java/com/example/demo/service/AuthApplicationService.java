package com.example.demo.service;

import cn.dev33.satoken.stp.StpUtil;
import com.example.demo.model.auth.AuthUser;
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
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthApplicationService {

    private final AuthAccountService authAccountService;
    private final AuthPermissionService authPermissionService;
    private final AuthContextService authContextService;
    private final PasswordRecoveryService passwordRecoveryService;

    public AuthApplicationService(AuthAccountService authAccountService,
                                  AuthPermissionService authPermissionService,
                                  AuthContextService authContextService,
                                  PasswordRecoveryService passwordRecoveryService) {
        this.authAccountService = authAccountService;
        this.authPermissionService = authPermissionService;
        this.authContextService = authContextService;
        this.passwordRecoveryService = passwordRecoveryService;
    }

    public ApiResponse<UserInfoResponse> register(RegisterRequest request) {
        AuthUser user = authAccountService.register(
                request.getUsername(),
                request.getPassword(),
                request.getEmail(),
                request.getRoleCode()
        );
        return ApiResponse.success(toUserInfo(user));
    }

    public ApiResponse<LoginResponse> login(LoginRequest request) {
        AuthUser user = authAccountService.authenticate(request.getUsername(), request.getPassword());
        StpUtil.login(user.getId());
        return ApiResponse.success(new LoginResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                StpUtil.getTokenValue(),
                authPermissionService.getRoleList(user.getId(), "login"),
                authPermissionService.getPermissionList(user.getId(), "login")
        ));
    }

    public ApiResponse<String> logout() {
        StpUtil.logout();
        return ApiResponse.success("退出登录成功");
    }

    public ApiResponse<UserInfoResponse> getCurrentUserInfo() {
        AuthUser user = authAccountService.requireById(authContextService.getCurrentUserId());
        return ApiResponse.success(toUserInfo(user));
    }

    public ApiResponse<String> changePassword(ChangePasswordRequest request) {
        String currentUserId = authContextService.getCurrentUserId();
        authAccountService.changePassword(
                currentUserId,
                request.getCurrentPassword(),
                request.getNewPassword(),
                request.getConfirmNewPassword()
        );
        StpUtil.logout(currentUserId);
        return ApiResponse.success("密码修改成功，请重新登录");
    }

    public ApiResponse<String> adminResetPassword(AdminResetPasswordRequest request) {
        String targetUserId = authAccountService.resetPasswordByUsername(
                request.getUsername(),
                request.getNewPassword(),
                request.getConfirmNewPassword()
        );
        StpUtil.logout(targetUserId);
        return ApiResponse.success("密码重置成功");
    }

    public ApiResponse<PasswordResetCodeResponse> requestPasswordResetCode(ForgotPasswordRequest request) {
        return ApiResponse.success(
                "重置码已生成，请在有效期内完成密码重置",
                passwordRecoveryService.createResetCode(request.getUsername())
        );
    }

    public ApiResponse<String> confirmForgotPassword(ForgotPasswordConfirmRequest request) {
        String targetUserId = passwordRecoveryService.confirmReset(request);
        StpUtil.logout(targetUserId);
        return ApiResponse.success("密码找回成功，请使用新密码登录");
    }

    private UserInfoResponse toUserInfo(AuthUser user) {
        List<String> roles = authPermissionService.getRoleList(user.getId(), "login");
        List<String> permissions = authPermissionService.getPermissionList(user.getId(), "login");
        return new UserInfoResponse(user.getId(), user.getUsername(), user.getEmail(), user.getStatus(), roles, permissions);
    }
}
