package com.wille.erp.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OpeningVO {
    private Long id;
    private String docNo;
    /** 10 草稿 / 20 已审核 / 50 已作废 */
    private Integer status;
    private Long warehouseId;
    private LocalDate docDate;
    private LocalDateTime auditTime;
    private String voidReason;
    private List<OpeningItemVO> items;

    @Data
    public static class OpeningItemVO {
        private Long id;
        private Integer lineNo;
        private Long skuId;
        private String skuName;
        private BigDecimal qty;
        private BigDecimal costPrice;
        private BigDecimal costAmount;
        private String lotNo;
        private LocalDate productionDate;
        private List<String> snList;
        private String remark;
    }
}
