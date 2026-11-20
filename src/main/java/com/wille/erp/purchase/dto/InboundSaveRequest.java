package com.wille.erp.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class InboundSaveRequest {
    @NotNull
    private Long supplierId;
    @NotNull
    private Long warehouseId;
    /** NULL = 无订单直接入库（spec §8-13） */
    private Long sourceOrderId;
    @Valid
    @NotEmpty(message = "入库行不能为空")
    private List<InboundItemRequest> items;
}
