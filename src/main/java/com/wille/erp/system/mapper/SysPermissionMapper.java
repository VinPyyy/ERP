package com.wille.erp.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.system.entity.SysPermission;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /** 用户权限码集合 = 角色并集（菜单 + 按钮），登录/me 与 Sa-Token 校验共用 */
    @Select("""
            SELECT DISTINCT p.perm_code FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            JOIN sys_user_role ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = #{userId}
              AND p.deleted = 0 AND rp.deleted = 0 AND ur.deleted = 0
            """)
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);
}
