package com.example.demo.service;

import com.example.demo.mapper.AuthPermissionMapper;
import com.example.demo.mapper.AuthRoleMapper;
import com.example.demo.mapper.AuthUserMapper;
import com.example.demo.model.auth.AuthPermission;
import com.example.demo.model.auth.AuthRole;
import com.example.demo.model.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthPermissionServiceTest {

    @Mock
    private AuthUserMapper authUserMapper;

    @Mock
    private AuthRoleMapper authRoleMapper;

    @Mock
    private AuthPermissionMapper authPermissionMapper;

    @InjectMocks
    private AuthPermissionService authPermissionService;

    @Test
    void shouldLoadRolesAndPermissionsForUser() {
        AuthUser user = new AuthUser();
        user.setId("u-1");
        user.setUsername("alice");
        user.setStatus("ACTIVE");

        AuthRole adminRole = new AuthRole();
        adminRole.setCode("admin");

        AuthPermission sessionCreate = new AuthPermission();
        sessionCreate.setCode("ai:session:create");

        AuthPermission sessionList = new AuthPermission();
        sessionList.setCode("ai:session:list");

        when(authUserMapper.selectById("u-1")).thenReturn(user);
        when(authRoleMapper.selectRolesByUserId("u-1")).thenReturn(List.of(adminRole));
        when(authPermissionMapper.selectPermissionsByUserId("u-1"))
                .thenReturn(List.of(sessionCreate, sessionList, sessionCreate));

        assertEquals(List.of("admin"), authPermissionService.getRoleList("u-1", "login"));
        assertEquals(List.of("ai:session:create", "ai:session:list"),
                authPermissionService.getPermissionList("u-1", "login"));
    }
}
