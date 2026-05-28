package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.model.auth.AuthRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuthRoleMapper extends BaseMapper<AuthRole> {

    @Select("SELECT r.* FROM auth_role r " +
            "INNER JOIN auth_user_role ur ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId} ORDER BY r.code")
    List<AuthRole> selectRolesByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM auth_role WHERE code = #{code} LIMIT 1")
    AuthRole selectByCode(@Param("code") String code);
}
