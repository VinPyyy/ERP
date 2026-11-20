package com.wille.erp.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 期初行请求：管批次必填 lotNo，管 SN 必填 snList 且数量=qty */
@Data
public class OpeningItemRequest {
    private Long skuId;
    private BigDecimal qty;
    /** 期初均价，允许 0，必填 */
    private BigDecimal costPrice;
    private String lotNo;
    private LocalDate productionDate;
    private List<String> snList;
    private String remark;
}
