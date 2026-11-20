package com.wille.erp.system.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfigUpdateRequest {

    @NotNull
    private String value;
}
