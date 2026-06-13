package com.example.demo.service;

import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.AuthRoleMapper;
import com.example.demo.mapper.AuthUserMapper;
import com.example.demo.mapper.AuthUserRoleMapper;
import com.example.demo.model.auth.AuthRole;
import com.example.demo.model.auth.AuthUser;
import com.example.demo.model.auth.AuthUserRole;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthAccountServiceTest {

    @Mock
    private AuthUserMapper authUserMapper;

    @Mock
    private AuthRoleMapper authRoleMapper;

    @Mock
    private AuthUserRoleMapper authUserRoleMapper;

    @Mock
    private AuthPermissionService authPermissionService;

    @InjectMocks
    private AuthAccountService authAccountService;

    @Test
    void shouldRegisterAndAuthenticateWithEncodedPassword() {
        AtomicReference<AuthUser> storedUser = new AtomicReference<>();
        AuthRole role = new AuthRole();
        role.setId("role-user");
        role.setCode("user");

        when(authRoleMapper.selectByCode("user")).thenReturn(role);
        when(authUserMapper.selectByUsername("alice")).thenAnswer(invocation -> storedUser.get());
        when(authUserMapper.selectByEmail("alice@example.com")).thenReturn(null);
        when(authUserMapper.insert(any(AuthUser.class))).thenAnswer(invocation -> {
            storedUser.set(invocation.getArgument(0));
            return 1;
        });
        when(authUserRoleMapper.insert(any(AuthUserRole.class))).thenReturn(1);

        AuthUser registered = authAccountService.register("alice", "secret123", "alice@example.com");
        AuthUser authenticated = authAccountService.authenticate("alice", "secret123");

        assertEquals(registered.getId(), authenticated.getId());
        assertNotEquals("secret123", storedUser.get().getPasswordHash());
        assertEquals("alice@example.com", storedUser.get().getEmail());
    }

    @Test
    void shouldChangePasswordAndRejectOldPasswordAfterUpdate() {
        AuthUser storedUser = new AuthUser();
        storedUser.setId("u-1");
        storedUser.setUsername("alice");
        storedUser.setStatus("ACTIVE");
        storedUser.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("oldSecret1"));

        when(authUserMapper.selectById("u-1")).thenReturn(storedUser);
        when(authUserMapper.selectByUsername("alice")).thenReturn(storedUser);
        when(authUserMapper.updateById(any(AuthUser.class))).thenAnswer(invocation -> {
            AuthUser updated = invocation.getArgument(0);
            storedUser.setPasswordHash(updated.getPasswordHash());
            storedUser.setUpdatedAt(updated.getUpdatedAt());
            return 1;
        });

        authAccountService.changePassword("u-1", "oldSecret1", "newSecret1", "newSecret1");

        assertThrows(BusinessException.class, () -> authAccountService.authenticate("alice", "oldSecret1"));
        AuthUser authenticated = authAccountService.authenticate("alice", "newSecret1");
        assertEquals("u-1", authenticated.getId());
    }

    @Test
    void shouldResetPasswordByUsername() {
        AuthUser storedUser = new AuthUser();
        storedUser.setId("u-1");
        storedUser.setUsername("alice");
        storedUser.setEmail("alice@example.com");
        storedUser.setStatus("ACTIVE");
        storedUser.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("oldSecret1"));

        when(authUserMapper.selectByUsername("alice")).thenReturn(storedUser);
        when(authUserMapper.updateById(any(AuthUser.class))).thenAnswer(invocation -> {
            AuthUser updated = invocation.getArgument(0);
            storedUser.setPasswordHash(updated.getPasswordHash());
            return 1;
        });

        try (MockedStatic<StpUtil> stpMock = mockStatic(StpUtil.class)) {
            stpMock.when(StpUtil::getLoginIdAsString).thenReturn("admin-1");
            when(authPermissionService.getRoleList("admin-1", "login")).thenReturn(List.of("admin"));

            String userId = authAccountService.resetPasswordByUsername("alice", "resetSecret1", "resetSecret1");

            assertEquals("u-1", userId);
            assertEquals("u-1", authAccountService.authenticate("alice", "resetSecret1").getId());
        }
    }

    @Test
    void shouldRejectDuplicateEmailDuringRegistration() {
        AuthUser existingUser = new AuthUser();
        existingUser.setId("u-2");
        existingUser.setEmail("alice@example.com");

        when(authUserMapper.selectByUsername("alice")).thenReturn(null);
        when(authUserMapper.selectByEmail("alice@example.com")).thenReturn(existingUser);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> authAccountService.register("alice", "secret123", "alice@example.com")
        );

        assertEquals("邮箱已被占用", error.getMessage());
    }
}
