package com.wille.erp.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.system.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 逻辑删除占用的用户名计数。raw SQL 绕过 @TableLogic 过滤：
     * uk_sys_user_username 不含 deleted 列，已删行仍占唯一索引，必须显式检查。
     */
    @Select("select count(*) from sys_user where username = #{username} and deleted = 1")
    int countDeletedByUsername(@Param("username") String username);
}
