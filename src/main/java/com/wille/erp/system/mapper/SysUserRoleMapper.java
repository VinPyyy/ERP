package com.wille.erp.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /** 中间表重绑用物理删（避免逻辑删残留撞 UNIQUE(user_id, role_id)） */
    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteHardByUserId(@Param("userId") Long userId);
}
