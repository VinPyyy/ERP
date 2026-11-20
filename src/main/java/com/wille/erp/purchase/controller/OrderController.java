package com.wille.erp.purchase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.R;
import com.wille.erp.purchase.dto.OrderSaveRequest;
import com.wille.erp.purchase.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchase/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Data
    public static class VoidRequest {
        @NotBlank(message = "作废原因必填")
        private String reason;
    }

    @PostMapping
    @SaCheckPermission("purchase:order:create")
    public R<Long> create(@Valid @RequestBody OrderSaveRequest request) {
        return R.ok(orderService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("purchase:order:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody OrderSaveRequest request) {
        orderService.update(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/audit")
    @SaCheckPermission("purchase:order:audit")
    public R<Void> audit(@PathVariable Long id) {
        orderService.audit(id);
        return R.ok();
    }

    @PostMapping("/{id}/void")
    @SaCheckPermission("purchase:order:void")
    public R<Void> voidOrder(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        orderService.voidOrder(id, request.getReason());
        return R.ok();
    }

    @PostMapping("/{id}/items/{itemId}/close")
    @SaCheckPermission("purchase:order:close")
    public R<Void> closeItem(@PathVariable Long id, @PathVariable Long itemId) {
        orderService.closeItem(id, itemId);
        return R.ok();
    }

    @PostMapping("/{id}/create-inbound")
    @SaCheckPermission("purchase:order:create-inbound")
    public R<Long> createInbound(@PathVariable Long id) {
        return R.ok(orderService.createInbound(id));
    }

    @org.springframework.web.bind.annotation.GetMapping
    @SaCheckPermission("purchase:order:list")
    public R<com.wille.erp.common.api.PageResult<com.wille.erp.purchase.entity.PurOrder>> page(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int pageNum,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int pageSize,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String keyword) {
        return R.ok(orderService.page(pageNum, pageSize, keyword));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    @SaCheckPermission("purchase:order:list")
    public R<com.wille.erp.purchase.dto.OrderVO> get(@PathVariable Long id) {
        return R.ok(orderService.get(id));
    }
}
