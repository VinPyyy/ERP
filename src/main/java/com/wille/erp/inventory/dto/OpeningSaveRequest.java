package com.wille.erp.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 期初单保存请求（草稿全量替换行） */
@Data
public class OpeningSaveRequest {

    @NotNull
    private Long warehouseId;

    @Valid
    @NotEmpty(message = "期初行不能为空")
    private List<OpeningItemRequest> items;
}
