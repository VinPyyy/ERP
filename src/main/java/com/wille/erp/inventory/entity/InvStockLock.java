package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 订单锁定明细（销售订单行 → 一行，spec §3e）：
 * 不变量 inv_stock.locked_qty = SUM(remain_qty) GROUP BY (warehouse, sku)；remain=0 行保留作历史。
 */
@Getter
@Setter
@TableName("inv_stock_lock")
public class InvStockLock extends BaseEntity {

    /** 一期恒 SAL_ORDER */
    private String lockDocType;
    private Long lockDocId;
    private Long lockItemId;
    private Long warehouseId;
    private Long skuId;
    /** 原始锁定量 */
    private BigDecimal lockQty;
    /** 剩余未核销 */
    private BigDecimal remainQty;
}
