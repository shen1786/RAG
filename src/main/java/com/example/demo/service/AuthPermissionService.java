package com.example.demo.service;

import cn.dev33.satoken.stp.StpInterface;
import com.example.demo.mapper.AuthPermissionMapper;
import com.example.demo.mapper.AuthRoleMapper;
import com.example.demo.mapper.AuthUserMapper;
import com.example.demo.model.auth.AuthPermission;
import com.example.demo.model.auth.AuthRole;
import com.example.demo.model.auth.AuthUser;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

@Component
public class AuthPermissionService implements StpInterface {

    private final AuthUserMapper authUserMapper;
    private final AuthRoleMapper authRoleMapper;
    private final AuthPermissionMapper authPermissionMapper;

    public AuthPermissionService(AuthUserMapper authUserMapper,
                                 AuthRoleMapper authRoleMapper,
                                 AuthPermissionMapper authPermissionMapper) {
        this.authUserMapper = authUserMapper;
        this.authRoleMapper = authRoleMapper;
        this.authPermissionMapper = authPermissionMapper;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        AuthUser user = authUserMapper.selectById(String.valueOf(loginId));
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            return List.of();
        }
        return authPermissionMapper.selectPermissionsByUserId(user.getId()).stream()
                .map(AuthPermission::getCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        AuthUser user = authUserMapper.selectById(String.valueOf(loginId));
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            return List.of();
        }
        return authRoleMapper.selectRolesByUserId(user.getId()).stream()
                .map(AuthRole::getCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
    }
}
