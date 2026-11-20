package com.wille.erp.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockVO {
    private Long warehouseId;
    private Long skuId;
    private String skuCode;
    private String skuName;
    private String unit;
    private BigDecimal qty;
    private BigDecimal lockedQty;
    private BigDecimal availableQty;
    /** 无 inventory:cost:view 时为 null（后端脱敏） */
    private BigDecimal costPrice;
    private BigDecimal costAmount;
}
