package com.wille.erp.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.system.entity.SysUserWarehouse;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysUserWarehouseMapper extends BaseMapper<SysUserWarehouse> {

    /** 可见仓库 id 集：含 0 表示全部仓库；空集 = 无可见仓库 */
    @Select("""
            SELECT warehouse_id FROM sys_user_warehouse
            WHERE user_id = #{userId} AND deleted = 0
            """)
    List<Long> selectWarehouseIdsByUserId(@Param("userId") Long userId);

    /** 中间表重绑用物理删（避免逻辑删残留撞 UNIQUE(user_id, warehouse_id)） */
    @Delete("DELETE FROM sys_user_warehouse WHERE user_id = #{userId}")
    int deleteHardByUserId(@Param("userId") Long userId);
}
