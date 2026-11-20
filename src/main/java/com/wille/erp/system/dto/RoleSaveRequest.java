package com.wille.erp.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RoleSaveRequest {

    @NotBlank
    private String roleCode;

    @NotBlank
    private String roleName;

    private String remark;
}
