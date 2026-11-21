package com.wille.erp.sale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ReturnSaveRequest {
    @NotNull(message = "客户必选")
    private Long customerId;
    @NotNull(message = "退货入库仓必选")
    private Long warehouseId;
    private Long sourceOutboundId;
    @Valid
    @NotEmpty(message = "退货行不能为空")
    private List<ReturnItemRequest> items;
}
