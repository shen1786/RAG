package com.example.demo.model.auth;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("auth_user_role")
public class AuthUserRole {
    @TableField("user_id")
    private String userId;
    @TableField("role_id")
    private String roleId;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
