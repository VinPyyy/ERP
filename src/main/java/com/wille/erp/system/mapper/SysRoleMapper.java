package com.wille.erp.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.system.entity.SysRole;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("""
            SELECT r.role_code FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.deleted = 0 AND ur.deleted = 0
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
