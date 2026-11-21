package com.wille.erp.sale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class OutboundSaveRequest {
    @NotNull(message = "客户必选")
    private Long customerId;
    @NotNull(message = "仓库必选")
    private Long warehouseId;
    private Long sourceOrderId;
    private String carrier;
    private String trackingNo;
    @Valid
    @NotEmpty(message = "出库行不能为空")
    private List<OutboundItemRequest> items;
}
