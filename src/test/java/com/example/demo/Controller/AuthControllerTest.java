package com.example.demo.Controller;

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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void shouldDelegateLogin() {
        AuthApplicationService authApplicationService = mock(AuthApplicationService.class);
        AuthController controller = new AuthController(authApplicationService);
        LoginRequest request = new LoginRequest("alice", "secret");
        LoginResponse response = new LoginResponse("u-1", "alice", "alice@example.com", "token", List.of("admin"), List.of("ai:session:create"));
        when(authApplicationService.login(request)).thenReturn(ApiResponse.success(response));

        ApiResponse<LoginResponse> result = controller.login(request);

        assertEquals(200, result.getCode());
        assertEquals("u-1", result.getData().getUserId());
    }

    @Test
    void shouldDelegateRegisterAndProfile() {
        AuthApplicationService authApplicationService = mock(AuthApplicationService.class);
        AuthController controller = new AuthController(authApplicationService);
        RegisterRequest registerRequest = new RegisterRequest("alice", "secret123", "alice@example.com", "admin");
        UserInfoResponse userInfo = new UserInfoResponse("u-1", "alice", "alice@example.com", "ACTIVE", List.of("admin"), List.of("ai:session:create"));

        when(authApplicationService.register(registerRequest)).thenReturn(ApiResponse.success(userInfo));
        when(authApplicationService.getCurrentUserInfo()).thenReturn(ApiResponse.success(userInfo));

        assertEquals("alice", controller.register(registerRequest).getData().getUsername());
        assertEquals("alice@example.com", controller.register(registerRequest).getData().getEmail());
        assertEquals("u-1", controller.me().getData().getUserId());
    }

    @Test
    void shouldDelegatePasswordOperations() {
        AuthApplicationService authApplicationService = mock(AuthApplicationService.class);
        AuthController controller = new AuthController(authApplicationService);

        ChangePasswordRequest changeRequest = new ChangePasswordRequest("oldSecret1", "newSecret1", "newSecret1");
        AdminResetPasswordRequest resetRequest = new AdminResetPasswordRequest("alice", "adminSecret1", "adminSecret1");
        ForgotPasswordRequest forgotRequest = new ForgotPasswordRequest("alice");
        ForgotPasswordConfirmRequest confirmRequest = new ForgotPasswordConfirmRequest("alice", "123456", "newSecret1", "newSecret1");

        when(authApplicationService.changePassword(changeRequest)).thenReturn(ApiResponse.success("密码修改成功，请重新登录"));
        when(authApplicationService.adminResetPassword(resetRequest)).thenReturn(ApiResponse.success("密码重置成功"));
        when(authApplicationService.requestPasswordResetCode(forgotRequest)).thenReturn(ApiResponse.success(new PasswordResetCodeResponse("123456", 600)));
        when(authApplicationService.confirmForgotPassword(confirmRequest)).thenReturn(ApiResponse.success("密码找回成功，请使用新密码登录"));

        assertEquals("密码修改成功，请重新登录", controller.changePassword(changeRequest).getMessage());
        assertEquals("密码重置成功", controller.resetPassword(resetRequest).getMessage());
        assertEquals("123456", controller.requestForgotPasswordCode(forgotRequest).getData().getResetCode());
        assertEquals("密码找回成功，请使用新密码登录", controller.confirmForgotPassword(confirmRequest).getMessage());
    }
}
