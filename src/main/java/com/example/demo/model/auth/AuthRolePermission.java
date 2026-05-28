package com.example.demo.model.auth;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("auth_role_permission")
public class AuthRolePermission {
    @TableField("role_id")
    private String roleId;
    @TableField("permission_id")
    private String permissionId;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
