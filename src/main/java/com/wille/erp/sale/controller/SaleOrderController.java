package com.wille.erp.sale.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.sale.dto.SaleDocOptionVO;
import com.wille.erp.sale.dto.SaleOrderSaveRequest;
import com.wille.erp.sale.dto.SaleOrderVO;
import com.wille.erp.sale.entity.SalOrder;
import com.wille.erp.sale.service.SaleOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/sale/orders")
@RequiredArgsConstructor
public class SaleOrderController {

    private final SaleOrderService orderService;

    @Data
    public static class VoidRequest {
        @NotBlank(message = "作废原因必填")
        private String reason;
    }

    @PostMapping
    @SaCheckPermission("sale:order:create")
    public R<Long> create(@Valid @RequestBody SaleOrderSaveRequest request) {
        return R.ok(orderService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("sale:order:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody SaleOrderSaveRequest request) {
        orderService.update(id, request);
        return R.ok();
    }

    /** 出库源单下拉：已审核订单；仅放行能建出库单的角色（reference-options 同源思路） */
    @GetMapping("/options")
    @SaCheckPermission("sale:outbound:create")
    public R<List<SaleDocOptionVO>> options() {
        return R.ok(orderService.auditedOptions());
    }

    @GetMapping
    @SaCheckPermission("sale:order:list")
    public R<PageResult<SalOrder>> page(@RequestParam(defaultValue = "1") int pageNum,
                                        @RequestParam(defaultValue = "20") int pageSize,
                                        @RequestParam(required = false) String keyword) {
        return R.ok(orderService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("sale:order:list")
    public R<SaleOrderVO> get(@PathVariable Long id) {
        return R.ok(orderService.get(id));
    }

    @PostMapping("/{id}/audit")
    @SaCheckPermission("sale:order:audit")
    public R<Void> audit(@PathVariable Long id) {
        orderService.audit(id);
        return R.ok();
    }

    @PostMapping("/{id}/void")
    @SaCheckPermission("sale:order:void")
    public R<Void> voidOrder(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        orderService.voidOrder(id, request.getReason());
        return R.ok();
    }

    @PostMapping("/{id}/items/{itemId}/close")
    @SaCheckPermission("sale:order:close")
    public R<Void> closeItem(@PathVariable Long id, @PathVariable Long itemId) {
        orderService.closeItem(id, itemId);
        return R.ok();
    }

    @PostMapping("/{id}/create-outbound")
    @SaCheckPermission("sale:order:create-outbound")
    public R<Long> createOutbound(@PathVariable Long id) {
        return R.ok(orderService.createOutbound(id));
    }
}
