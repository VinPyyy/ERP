package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 批次库存行（纯数量分账）：不变量 SUM(批次行.qty) = 主行.qty（管批次 SKU） */
@Getter
@Setter
@TableName("inv_lot_stock")
public class InvLotStock extends BaseEntity {

    private Long warehouseId;
    private Long skuId;
    private Long lotId;
    private BigDecimal qty;
}
