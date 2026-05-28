package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.model.auth.AuthUserRole;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthUserRoleMapper extends BaseMapper<AuthUserRole> {
}
