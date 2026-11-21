package com.wille.erp.sale.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class QuoteItemRequest {
    @NotNull(message = "商品必选")
    private Long skuId;
    @NotNull(message = "数量必填")
    private BigDecimal qty;
    private BigDecimal price;
    private String remark;
}
