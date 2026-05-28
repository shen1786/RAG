package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.mapper.AuthRoleMapper;
import com.example.demo.mapper.AuthUserMapper;
import com.example.demo.mapper.AuthUserRoleMapper;
import com.example.demo.model.auth.AuthRole;
import com.example.demo.model.auth.AuthUser;
import com.example.demo.model.auth.AuthUserRole;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthAccountService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final AuthUserMapper authUserMapper;
    private final AuthRoleMapper authRoleMapper;
    private final AuthUserRoleMapper authUserRoleMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthAccountService(AuthUserMapper authUserMapper,
                              AuthRoleMapper authRoleMapper,
                              AuthUserRoleMapper authUserRoleMapper) {
        this.authUserMapper = authUserMapper;
        this.authRoleMapper = authRoleMapper;
        this.authUserRoleMapper = authUserRoleMapper;
    }

    public AuthUser register(String username, String password, String email, String roleCode) {
        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        if (authUserMapper.selectByUsername(normalizedUsername) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (authUserMapper.selectByEmail(normalizedEmail) != null) {
            throw new IllegalArgumentException("邮箱已被占用");
        }

        AuthRole role = authRoleMapper.selectByCode(resolveRoleCode(roleCode));
        if (role == null) {
            throw new IllegalArgumentException("角色不存在");
        }

        AuthUser user = new AuthUser();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(normalizedUsername);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        authUserMapper.insert(user);

        AuthUserRole userRole = new AuthUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRole.setCreatedAt(LocalDateTime.now());
        authUserRoleMapper.insert(userRole);
        return user;
    }

    public AuthUser authenticate(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        AuthUser user = authUserMapper.selectByUsername(normalizedUsername);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException("账号已被禁用");
        }
        return user;
    }

    public void changePassword(String userId, String currentPassword, String newPassword, String confirmNewPassword) {
        AuthUser user = requireById(userId);
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("当前密码不能为空");
        }
        validateNewPassword(newPassword, confirmNewPassword);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码错误");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        updatePasswordHash(user, newPassword);
    }

    public String resetPasswordByUsername(String username, String newPassword, String confirmNewPassword) {
        String normalizedUsername = normalizeUsername(username);
        AuthUser user = requireByUsername(normalizedUsername);
        validateNewPassword(newPassword, confirmNewPassword);
        updatePasswordHash(user, newPassword);
        return user.getId();
    }

    public AuthUser requireByUsername(String username) {
        String normalizedUsername = normalizeUsername(username);
        AuthUser user = authUserMapper.selectByUsername(normalizedUsername);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user;
    }

    public AuthUser requireById(String userId) {
        AuthUser user = authUserMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user;
    }

    public void ensureSeedRoleExists(String roleCode) {
        if (authRoleMapper.selectByCode(roleCode) != null) {
            return;
        }
        AuthRole role = new AuthRole();
        role.setId(UUID.randomUUID().toString());
        role.setCode(roleCode);
        role.setName(roleCode);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        authRoleMapper.insert(role);
    }

    public boolean hasAnyUser() {
        return authUserMapper.selectCount(new QueryWrapper<>()) > 0;
    }

    public String normalizeUsername(String username) {
        if (username == null || username.trim().length() < 3 || username.trim().length() > 32) {
            throw new IllegalArgumentException("用户名长度必须在 3 到 32 个字符之间");
        }
        return username.trim();
    }

    public String normalizeEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        String normalizedEmail = email.trim().toLowerCase();
        if (normalizedEmail.length() > 255 || !EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        return normalizedEmail;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new IllegalArgumentException("密码长度必须在 6 到 64 个字符之间");
        }
    }

    private void validateNewPassword(String newPassword, String confirmNewPassword) {
        validatePassword(newPassword);
        if (confirmNewPassword == null || !newPassword.equals(confirmNewPassword)) {
            throw new IllegalArgumentException("两次输入的新密码不一致");
        }
    }

    private void updatePasswordHash(AuthUser user, String rawPassword) {
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setUpdatedAt(LocalDateTime.now());
        authUserMapper.updateById(user);
    }

    private String resolveRoleCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return "user";
        }
        return roleCode.trim();
    }
}
