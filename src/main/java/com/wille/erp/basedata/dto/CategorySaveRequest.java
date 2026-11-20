package com.wille.erp.basedata.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CategorySaveRequest {

    @NotBlank
    private String name;

    private Long parentId;
    private Integer sortNo;
}
