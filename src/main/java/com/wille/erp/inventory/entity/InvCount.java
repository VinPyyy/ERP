package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseDocEntity;
import lombok.Getter;
import lombok.Setter;

/** 盘点单头：status=COUNTING(15) 本身即范围锁（ADR-0004） */
@TableName("inv_count")
@Getter
@Setter
public class InvCount extends BaseDocEntity {
    private Long warehouseId;
    private String scopeDesc;
}
