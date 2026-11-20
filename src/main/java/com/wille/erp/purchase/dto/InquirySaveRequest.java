package com.wille.erp.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class InquirySaveRequest {
    private String title;
    @Valid
    @NotEmpty(message = "询价行不能为空")
    private List<InquiryItemRequest> items;
}
