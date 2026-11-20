package com.wille.erp.purchase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ReturnItemRequest {
    private Long sourceInboundItemId;
    private Long skuId;
    private BigDecimal qty;
    /** 有原单=原单价不可改（服务端强制）；无原单手录 */
    private BigDecimal price;
    private String lotNo;
    private List<String> snList;
    private String reason;
}
