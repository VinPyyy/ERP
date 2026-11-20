package com.wille.erp.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 期初单保存请求（草稿全量替换行） */
@Data
public class OpeningSaveRequest {

    @NotNull
    private Long warehouseId;

    /** 草稿允许空行（明细走 Excel 导入）；审核时校验非空（M3b 修复轮 I5） */
    @Valid
    private List<OpeningItemRequest> items;
}
