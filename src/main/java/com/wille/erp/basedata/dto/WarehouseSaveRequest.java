package com.wille.erp.basedata.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WarehouseSaveRequest {

    @NotBlank
    private String code;

    @NotBlank
    private String name;

    private String address;
    private String keeper;
    private Integer status;
    private Integer sortNo;
    private String remark;
}
