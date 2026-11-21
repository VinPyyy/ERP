package com.wille.erp.sale.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.sale.dto.QuoteSaveRequest;
import com.wille.erp.sale.dto.QuoteVO;
import com.wille.erp.sale.entity.SalQuote;
import com.wille.erp.sale.service.QuoteService;
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

@RestController
@RequestMapping("/api/sale/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService quoteService;

    @Data
    public static class VoidRequest {
        @NotBlank(message = "作废原因必填")
        private String reason;
    }

    @Data
    public static class ConvertRequest {
        @NotNull(message = "请选择订单仓库")
        private Long warehouseId;
    }

    @PostMapping
    @SaCheckPermission("sale:quote:create")
    public R<Long> create(@Valid @RequestBody QuoteSaveRequest request) {
        return R.ok(quoteService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("sale:quote:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody QuoteSaveRequest request) {
        quoteService.update(id, request);
        return R.ok();
    }

    @GetMapping
    @SaCheckPermission("sale:quote:list")
    public R<PageResult<SalQuote>> page(@RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "20") int pageSize,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(quoteService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("sale:quote:list")
    public R<QuoteVO> get(@PathVariable Long id) {
        return R.ok(quoteService.get(id));
    }

    @PostMapping("/{id}/audit")
    @SaCheckPermission("sale:quote:audit")
    public R<Void> audit(@PathVariable Long id) {
        quoteService.audit(id);
        return R.ok();
    }

    @PostMapping("/{id}/void")
    @SaCheckPermission("sale:quote:void")
    public R<Void> voidQuote(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        quoteService.voidQuote(id, request.getReason());
        return R.ok();
    }

    @PostMapping("/{id}/convert-to-order")
    @SaCheckPermission("sale:quote:convert")
    public R<Long> convert(@PathVariable Long id, @Valid @RequestBody ConvertRequest request) {
        return R.ok(quoteService.convertToOrder(id, request.getWarehouseId()));
    }
}
