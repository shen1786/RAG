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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthApplicationServiceTest {

    @Mock
    private AuthAccountService authAccountService;

    @Mock
    private AuthPermissionService authPermissionService;

    @Mock
    private AuthContextService authContextService;

    @Mock
    private PasswordRecoveryService passwordRecoveryService;

    @InjectMocks
    private AuthApplicationService authApplicationService;

    @Test
    void shouldLoginAndReturnTokenMetadata() {
        AuthUser user = new AuthUser();
        user.setId("u-1");
        user.setUsername("alice");
        user.setStatus("ACTIVE");

        when(authAccountService.authenticate("alice", "secret")).thenReturn(user);
        when(authPermissionService.getRoleList("u-1", "login")).thenReturn(List.of("admin"));
        when(authPermissionService.getPermissionList("u-1", "login")).thenReturn(List.of("ai:session:create"));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(() -> StpUtil.getTokenValue()).thenReturn("token-123");

            ApiResponse<LoginResponse> response = authApplicationService.login(new LoginRequest("alice", "secret"));

            stpUtil.verify(() -> StpUtil.login("u-1"));
            assertEquals(200, response.getCode());
            assertEquals("token-123", response.getData().getTokenValue());
        }
    }

    @Test
    void shouldResolveCurrentUserProfile() {
        AuthUser user = new AuthUser();
        user.setId("u-1");
        user.setUsername("alice");
        user.setStatus("ACTIVE");

        when(authContextService.getCurrentUserId()).thenReturn("u-1");
        when(authAccountService.requireById("u-1")).thenReturn(user);
        when(authPermissionService.getRoleList("u-1", "login")).thenReturn(List.of("admin"));
        when(authPermissionService.getPermissionList("u-1", "login")).thenReturn(List.of("document:list"));

        ApiResponse<UserInfoResponse> response = authApplicationService.getCurrentUserInfo();

        assertEquals("alice", response.getData().getUsername());
        assertEquals(List.of("document:list"), response.getData().getPermissions());
    }

    @Test
    void shouldRegisterUser() {
        AuthUser user = new AuthUser();
        user.setId("u-1");
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setStatus("ACTIVE");

        when(authAccountService.register("alice", "secret123", "alice@example.com")).thenReturn(user);
        when(authPermissionService.getRoleList("u-1", "login")).thenReturn(List.of("user"));
        when(authPermissionService.getPermissionList("u-1", "login")).thenReturn(List.of("document:list"));

        ApiResponse<UserInfoResponse> response = authApplicationService.register(new RegisterRequest("alice", "secret123", "alice@example.com"));

        assertEquals("u-1", response.getData().getUserId());
        assertEquals("alice@example.com", response.getData().getEmail());
        assertEquals(List.of("user"), response.getData().getRoles());
    }

    @Test
    void shouldChangePasswordAndLogoutCurrentUser() {
        when(authContextService.getCurrentUserId()).thenReturn("u-1");

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            ApiResponse<String> response = authApplicationService.changePassword(
                    new ChangePasswordRequest("oldSecret1", "newSecret1", "newSecret1")
            );

            stpUtil.verify(() -> StpUtil.logout("u-1"));
            assertEquals("密码修改成功，请重新登录", response.getMessage());
        }
    }

    @Test
    void shouldResetPasswordAsAdminAndLogoutTargetUser() {
        when(authAccountService.resetPasswordByUsername("alice", "newSecret1", "newSecret1")).thenReturn("u-2");

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            ApiResponse<String> response = authApplicationService.adminResetPassword(
                    new AdminResetPasswordRequest("alice", "newSecret1", "newSecret1")
            );

            stpUtil.verify(() -> StpUtil.logout("u-2"));
            assertEquals("密码重置成功", response.getMessage());
        }
    }

    @Test
    void shouldRequestResetCode() {
        when(passwordRecoveryService.createResetCode("alice")).thenReturn(new PasswordResetCodeResponse("123456", 600));

        ApiResponse<PasswordResetCodeResponse> response = authApplicationService.requestPasswordResetCode(
                new ForgotPasswordRequest("alice")
        );

        assertEquals("123456", response.getData().getResetCode());
    }

    @Test
    void shouldConfirmForgotPasswordAndLogoutUser() {
        when(passwordRecoveryService.confirmReset(
                new ForgotPasswordConfirmRequest("alice", "123456", "newSecret1", "newSecret1"),
                "127.0.0.1"))
                .thenReturn("u-3");

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            ApiResponse<String> response = authApplicationService.confirmForgotPassword(
                    new ForgotPasswordConfirmRequest("alice", "123456", "newSecret1", "newSecret1"),
                    "127.0.0.1"
            );

            stpUtil.verify(() -> StpUtil.logout("u-3"));
            assertEquals("密码找回成功，请使用新密码登录", response.getMessage());
        }
    }
}
