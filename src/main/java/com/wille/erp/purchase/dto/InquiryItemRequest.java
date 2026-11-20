package com.wille.erp.purchase.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class InquiryItemRequest {
    private Long skuId;
    private Long supplierId;
    private BigDecimal qty;
    private BigDecimal price;
    private Integer leadTimeDays;
    private String remark;
}
