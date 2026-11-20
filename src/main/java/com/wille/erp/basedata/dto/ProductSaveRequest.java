package com.wille.erp.basedata.dto;

import com.wille.erp.common.enums.MgmtMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductSaveRequest {

    @NotBlank
    private String skuCode;

    @NotBlank
    private String name;

    private String spec;

    @NotBlank
    private String unit;

    private String barcode;
    private Long categoryId;

    @NotNull
    private MgmtMode mgmtMode;

    /** 管批次/批次+SN 模式必填（应用层校验） */
    private Integer shelfLifeDays;

    private Integer expireWarnDays;
    private BigDecimal refSalePrice;
    private Integer status;
    private String remark;
}
