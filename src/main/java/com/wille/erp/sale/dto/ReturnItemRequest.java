package com.wille.erp.sale.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ReturnItemRequest {
    private Long sourceOutboundItemId;
    @NotNull(message = "商品必选")
    private Long skuId;
    @NotNull(message = "数量必填")
    private BigDecimal qty;
    private BigDecimal price;
    private String lotNo;
    private List<String> snList;
    private String reason;
}
