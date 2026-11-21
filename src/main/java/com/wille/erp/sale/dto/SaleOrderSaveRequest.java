package com.wille.erp.sale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class SaleOrderSaveRequest {
    @NotNull(message = "客户必选")
    private Long customerId;
    @NotNull(message = "仓库必选")
    private Long warehouseId;
    private LocalDate expectDate;
    @Valid
    @NotEmpty(message = "订单行不能为空")
    private List<SaleOrderItemRequest> items;
}
