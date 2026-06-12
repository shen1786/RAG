package com.example.demo.Controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckLogin;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.auth.AdminResetPasswordRequest;
import com.example.demo.model.dto.auth.ChangePasswordRequest;
import com.example.demo.model.dto.auth.ForgotPasswordConfirmRequest;
import com.example.demo.model.dto.auth.ForgotPasswordRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.demo.model.dto.auth.LoginRequest;
import com.example.demo.model.dto.auth.LoginResponse;
import com.example.demo.model.dto.auth.PasswordResetCodeResponse;
import com.example.demo.model.dto.auth.RegisterRequest;
import com.example.demo.model.dto.auth.UserInfoResponse;
import com.example.demo.service.AuthApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与权限控制层
 * 提供用户注册、登录、登出、个人信息查询、密码修改与找回等相关 RESTful API 接口。
 * 使用 Sa-Token 进行登录状态校验 (@SaCheckLogin) 与细粒度权限校验 (@SaCheckPermission)。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "认证管理", description = "注册、登录、密码修改与找回")
public class AuthController {

    private final AuthApplicationService authApplicationService;

    /**
     * 用户注册接口
     * 允许匿名访问，用于新用户注册账号。
     *
     * @param request 包含用户名、密码等注册信息的请求体
     * @return 注册成功后的用户信息
     */
    @SaIgnore
    @PostMapping("/register")
    public ApiResponse<UserInfoResponse> register(@Valid @RequestBody RegisterRequest request) {
        return authApplicationService.register(request);
    }

    /**
     * 用户登录接口
     * 允许匿名访问，登录成功后会返回 Sa-Token 签发的 token 凭证。
     *
     * @param request 包含用户名、密码的登录请求体
     * @return 包含 token、有效期及用户基本信息的登录响应
     */
    @SaIgnore
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return authApplicationService.login(request);
    }

    /**
     * 用户登出接口
     * 需要用户已处于登录状态，注销当前的 Sa-Token 会话。
     *
     * @return 登出结果提示信息
     */
    @SaCheckLogin
    @PostMapping("/logout")
    public ApiResponse<String> logout() {
        return authApplicationService.logout();
    }

    /**
     * 获取当前登录用户信息
     * 需要用户已处于登录状态，返回当前会话对应的用户详细资料。
     *
     * @return 当前登录用户的详细信息
     */
    @SaCheckLogin
    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> me() {
        return authApplicationService.getCurrentUserInfo();
    }

    /**
     * 用户自主修改密码
     * 需处于登录状态，且拥有 "auth:password:change" 权限。
     *
     * @param request 包含旧密码、新密码和确认新密码的请求体
     * @return 修改成功与否的提示信息
     */
    @SaCheckLogin
    @SaCheckPermission("auth:password:change")
    @PostMapping("/password/change")
    public ApiResponse<String> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return authApplicationService.changePassword(request);
    }

    /**
     * 管理员重置普通用户密码
     * 需处于登录状态，且拥有 "user:password:reset" 权限（通常为管理员角色）。
     *
     * @param request 包含目标用户名、新密码等重置信息的请求体
     * @return 重置成功与否的提示信息
     */
    @SaCheckLogin
    @SaCheckPermission("user:password:reset")
    @PostMapping("/password/reset")
    public ApiResponse<String> resetPassword(@Valid @RequestBody AdminResetPasswordRequest request) {
        return authApplicationService.adminResetPassword(request);
    }

    /**
     * 忘记密码 - 申请密码重置验证码
     * 允许匿名访问。验证码生成后将通过配置的真实邮箱通道（或在未配置 SMTP 时降级输出至本地模拟邮箱文件/后端日志）进行投递。
     *
     * @param request 包含申请账号用户名的请求体
     * @return 包含验证码失效时间的响应（根据配置决定是否在返回体中泄露明文验证码，生产环境下不外露）
     */
    @SaIgnore
    @PostMapping("/password/forgot/request")
    public ApiResponse<PasswordResetCodeResponse> requestForgotPasswordCode(@Valid @RequestBody ForgotPasswordRequest request) {
        return authApplicationService.requestPasswordResetCode(request);
    }

    /**
     * 忘记密码 - 确认重置密码
     * 允许匿名访问。用户提供正确的 6 位数字重置码及新密码，即可完成密码修改。
     *
     * @param request 包含用户名、重置验证码和新密码的请求体
     * @return 密码重置结果提示信息
     */
    @SaIgnore
    @PostMapping("/password/forgot/confirm")
    public ApiResponse<String> confirmForgotPassword(@Valid @RequestBody ForgotPasswordConfirmRequest request) {
        return authApplicationService.confirmForgotPassword(request);
    }
}
