package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.model.auth.AuthUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthUserMapper extends BaseMapper<AuthUser> {

    @Select("SELECT id, username, password_hash AS passwordHash, status, " +
            "email, created_at AS createdAt, updated_at AS updatedAt " +
            "FROM auth_user WHERE username = #{username} LIMIT 1")
    AuthUser selectByUsername(@Param("username") String username);

    @Select("SELECT id, username, password_hash AS passwordHash, status, " +
            "email, created_at AS createdAt, updated_at AS updatedAt " +
            "FROM auth_user WHERE email = #{email} LIMIT 1")
    AuthUser selectByEmail(@Param("email") String email);
}
