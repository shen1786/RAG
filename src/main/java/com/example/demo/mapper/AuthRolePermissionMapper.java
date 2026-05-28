package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.model.auth.AuthRolePermission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthRolePermissionMapper extends BaseMapper<AuthRolePermission> {
}
