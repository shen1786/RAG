package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.Config.AuthSeedProperties;
import com.example.demo.mapper.AuthPermissionMapper;
import com.example.demo.mapper.AuthRoleMapper;
import com.example.demo.mapper.AuthRolePermissionMapper;
import com.example.demo.model.auth.AuthPermission;
import com.example.demo.model.auth.AuthRole;
import com.example.demo.model.auth.AuthRolePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthSeedService {

    private final AuthRoleMapper authRoleMapper;
    private final AuthPermissionMapper authPermissionMapper;
    private final AuthRolePermissionMapper authRolePermissionMapper;

    public void seedRolesAndPermissions() {
        AuthRole adminRole = ensureRole("admin", "管理员");
        AuthRole userRole = ensureRole("user", "普通用户");

        seedRolePermissions(userRole, new ArrayList<>(AuthSeedProperties.USER_PERMISSIONS));

        List<String> adminPermissions = new ArrayList<>(AuthSeedProperties.USER_PERMISSIONS);
        adminPermissions.addAll(AuthSeedProperties.ADMIN_EXTRA_PERMISSIONS);
        seedRolePermissions(adminRole, adminPermissions);
    }

    private AuthRole ensureRole(String code, String name) {
        AuthRole role = authRoleMapper.selectByCode(code);
        if (role != null) {
            return role;
        }
        AuthRole created = new AuthRole();
        created.setId(UUID.randomUUID().toString());
        created.setCode(code);
        created.setName(name);
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(LocalDateTime.now());
        authRoleMapper.insert(created);
        return created;
    }

    private void seedRolePermissions(AuthRole role, List<String> permissionCodes) {
        for (String permissionCode : permissionCodes) {
            AuthPermission permission = ensurePermission(permissionCode);
            QueryWrapper<AuthRolePermission> wrapper = new QueryWrapper<>();
            wrapper.eq("role_id", role.getId()).eq("permission_id", permission.getId());
            if (authRolePermissionMapper.selectCount(wrapper) > 0) {
                continue;
            }
            AuthRolePermission relation = new AuthRolePermission();
            relation.setRoleId(role.getId());
            relation.setPermissionId(permission.getId());
            relation.setCreatedAt(LocalDateTime.now());
            authRolePermissionMapper.insert(relation);
        }
    }

    private AuthPermission ensurePermission(String code) {
        QueryWrapper<AuthPermission> wrapper = new QueryWrapper<>();
        wrapper.eq("code", code).last("LIMIT 1");
        AuthPermission permission = authPermissionMapper.selectOne(wrapper);
        if (permission != null) {
            return permission;
        }
        AuthPermission created = new AuthPermission();
        created.setId(UUID.randomUUID().toString());
        created.setCode(code);
        created.setName(code);
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(LocalDateTime.now());
        authPermissionMapper.insert(created);
        return created;
    }
}
