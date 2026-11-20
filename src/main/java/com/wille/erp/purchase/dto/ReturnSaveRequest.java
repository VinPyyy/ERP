package com.wille.erp.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ReturnSaveRequest {
    @NotNull
    private Long supplierId;
    @NotNull
    private Long warehouseId;
    /** NULL = 无原单退货（spec §8-6 用户裁决允许） */
    private Long sourceInboundId;
    @Valid
    @NotEmpty(message = "退货行不能为空")
    private List<ReturnItemRequest> items;
}
