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

    /** 已删行仍占 DB 唯一索引 uk_sys_role_code：显式计数供"曾被使用"友好拦截（docs/system/username-lifecycle.md 通则） */
    @Select("select count(*) from sys_role where role_code = #{code} and deleted = 1")
    long countDeletedByCode(@Param("code") String code);
}
