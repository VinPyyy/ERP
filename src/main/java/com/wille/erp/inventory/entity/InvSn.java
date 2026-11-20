package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import com.wille.erp.common.enums.SnStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * SN 主档（一行一物，spec §3b）：两态 IN_STOCK ⇄ OUT；
 * 不变量 COUNT(status=IN_STOCK 且 warehouse=X) = 主行.qty（管 SN SKU）。
 */
@Getter
@Setter
@TableName("inv_sn")
public class InvSn extends BaseEntity {

    private Long skuId;
    private String snCode;
    /** 批次+SN 模式必填；纯 SN 为空 */
    private Long lotId;
    private SnStatus status;
    /** 在库有值，出库置 NULL */
    private Long warehouseId;

    private String inDocType;
    private Long inDocId;
    private String inDocNo;
    private LocalDateTime inTime;

    private String outDocType;
    private Long outDocId;
    private String outDocNo;
    private LocalDateTime outTime;
}
