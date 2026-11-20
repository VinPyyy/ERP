package com.wille.erp.inventory.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SnVO {
    private Long id;
    private Long skuId;
    private String snCode;
    private Long lotId;
    /** IN_STOCK / OUT */
    private String status;
    private Long warehouseId;
    private String inDocNo;
    private LocalDateTime inTime;
    private String outDocNo;
    private LocalDateTime outTime;
    private String remark;
}
