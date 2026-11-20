package com.wille.erp.purchase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class InquiryVO {
    private Long id;
    private String docNo;
    private Integer status;
    private String title;
    private LocalDate docDate;
    private LocalDateTime auditTime;
    private String voidReason;
    private List<InquiryItemVO> items;

    @Data
    public static class InquiryItemVO {
        private Long id;
        private Integer lineNo;
        private Long skuId;
        private String skuName;
        private String unit;
        private Long supplierId;
        private BigDecimal qty;
        private BigDecimal price;
        private BigDecimal amount;
        private Integer leadTimeDays;
        private Integer selected;
    }
}
