package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.model.auth.AuthPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuthPermissionMapper extends BaseMapper<AuthPermission> {

    @Select("SELECT DISTINCT p.* FROM auth_permission p " +
            "INNER JOIN auth_role_permission rp ON rp.permission_id = p.id " +
            "INNER JOIN auth_user_role ur ON ur.role_id = rp.role_id " +
            "WHERE ur.user_id = #{userId} ORDER BY p.code")
    List<AuthPermission> selectPermissionsByUserId(@Param("userId") String userId);
}
