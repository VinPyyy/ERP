package com.wille.erp.purchase.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderItemRequest {
    private Long skuId;
    private BigDecimal qty;
    private BigDecimal price;
    private String remark;
}
