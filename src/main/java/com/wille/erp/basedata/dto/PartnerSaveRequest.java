package com.wille.erp.basedata.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 客户/供应商共用保存结构（GLOSSARY"客户 / 供应商"：结构对称）。
 */
@Data
public class PartnerSaveRequest {

    @NotBlank
    private String code;

    @NotBlank
    private String name;

    private String contact;
    private String phone;
    private String address;
    /** 税号（二期预埋） */
    private String taxNo;
    private Integer status;
    private String remark;
}
