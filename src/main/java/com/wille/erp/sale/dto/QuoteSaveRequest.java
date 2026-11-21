package com.wille.erp.sale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class QuoteSaveRequest {
    @NotNull(message = "客户必选")
    private Long customerId;
    /** 报价有效期，可空=长期（spec §2.5 NULL 允许） */
    private LocalDate validUntil;
    @Valid
    @NotEmpty(message = "报价行不能为空")
    private List<QuoteItemRequest> items;
}
