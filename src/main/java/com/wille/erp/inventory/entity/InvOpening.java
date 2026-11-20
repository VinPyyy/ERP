package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseDocEntity;
import lombok.Getter;
import lombok.Setter;

/** 期初库存单头：一仓一期初单（约束在应用层，spec §2.3） */
@Getter
@Setter
@TableName("inv_opening")
public class InvOpening extends BaseDocEntity {

    private Long warehouseId;
}
