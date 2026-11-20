package com.wille.erp.purchase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.purchase.dto.InquirySaveRequest;
import com.wille.erp.purchase.entity.PurInquiry;
import com.wille.erp.purchase.dto.InquiryVO;
import com.wille.erp.purchase.service.InquiryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/purchase/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;

    @Data
    public static class VoidRequest {
        @NotBlank(message = "作废原因必填")
        private String reason;
    }

    @Data
    public static class SelectRequest {
        @NotNull
        private Boolean selected;
    }

    @Data
    public static class ConvertRequest {
        @NotNull(message = "请选择订单入库仓库")
        private Long warehouseId;
    }

    @PostMapping
    @SaCheckPermission("purchase:inquiry:create")
    public R<Long> create(@Valid @RequestBody InquirySaveRequest request) {
        return R.ok(inquiryService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("purchase:inquiry:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody InquirySaveRequest request) {
        inquiryService.update(id, request);
        return R.ok();
    }

    @GetMapping
    @SaCheckPermission("purchase:inquiry:list")
    public R<PageResult<PurInquiry>> page(@RequestParam(defaultValue = "1") int pageNum,
                                         @RequestParam(defaultValue = "20") int pageSize,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(inquiryService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("purchase:inquiry:list")
    public R<InquiryVO> get(@PathVariable Long id) {
        return R.ok(inquiryService.get(id));
    }

    @PostMapping("/{id}/audit")
    @SaCheckPermission("purchase:inquiry:audit")
    public R<Void> audit(@PathVariable Long id) {
        inquiryService.audit(id);
        return R.ok();
    }

    @PostMapping("/{id}/void")
    @SaCheckPermission("purchase:inquiry:void")
    public R<Void> voidInquiry(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        inquiryService.voidInquiry(id, request.getReason());
        return R.ok();
    }

    @PutMapping("/{id}/items/{itemId}/select")
    @SaCheckPermission("purchase:inquiry:edit")
    public R<Void> select(@PathVariable Long id, @PathVariable Long itemId,
                          @Valid @RequestBody SelectRequest request) {
        inquiryService.selectLine(id, itemId, Boolean.TRUE.equals(request.getSelected()));
        return R.ok();
    }

    @PostMapping("/{id}/convert-to-orders")
    @SaCheckPermission("purchase:inquiry:convert")
    public R<List<Long>> convert(@PathVariable Long id, @Valid @RequestBody ConvertRequest request) {
        return R.ok(inquiryService.convertToOrders(id, request.getWarehouseId()));
    }
}
