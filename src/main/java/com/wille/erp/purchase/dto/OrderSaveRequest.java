package com.wille.erp.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class OrderSaveRequest {
    @NotNull
    private Long supplierId;
    @NotNull
    private Long warehouseId;
    private LocalDate expectDate;
    @Valid
    @NotEmpty(message = "订单行不能为空")
    private List<OrderItemRequest> items;
}
