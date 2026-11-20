package com.wille.erp.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户可见仓库（数据权限）。warehouseId=0 表示全部仓库（含未来新增）；
 * 无绑定记录 = 看不到任何库存数据（spec §2.1）。
 */
@Getter
@Setter
@TableName("sys_user_warehouse")
public class SysUserWarehouse extends BaseEntity {

    public static final long ALL_WAREHOUSES = 0L;

    private Long userId;
    private Long warehouseId;
}
