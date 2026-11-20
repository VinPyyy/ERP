package com.wille.erp.purchase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class InboundItemRequest {
    private Long sourceOrderItemId;
    private Long skuId;
    private BigDecimal qty;
    /** 实际结算价：默认带订单价可改（spec §8-11） */
    private BigDecimal price;
    private String lotNo;
    private LocalDate productionDate;
    private List<String> snList;
    private String remark;
}
