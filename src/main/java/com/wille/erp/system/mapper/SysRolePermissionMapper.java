package com.wille.erp.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.system.entity.SysRolePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    /** 中间表重授权使用物理删（避免逻辑删残留撞 UNIQUE(role_id, permission_id)） */
    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteHardByRoleId(@Param("roleId") Long roleId);
}
