package com.wille.erp.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FlowVO {
    private Long id;
    private Long warehouseId;
    private Long skuId;
    private String skuCode;
    private String skuName;
    private Long lotId;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal costPrice;
    private BigDecimal costAmount;
    private BigDecimal balanceQty;
    private BigDecimal balanceCostAmount;
    private BigDecimal balanceCostPrice;
    private String bizType;
    private String bizDocNo;
    private LocalDateTime createTime;
}
