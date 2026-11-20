package com.wille.erp.purchase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.R;
import com.wille.erp.purchase.dto.InboundSaveRequest;
import com.wille.erp.purchase.service.InboundService;
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
@RequestMapping("/api/purchase/inbounds")
@RequiredArgsConstructor
public class InboundController {

    private final InboundService inboundService;
    private final com.wille.erp.purchase.service.ReturnService returnService;

    @Data
    public static class VoidRequest {
        @NotBlank(message = "作废原因必填")
        private String reason;
    }

    @PostMapping
    @SaCheckPermission("purchase:inbound:create")
    public R<Long> create(@Valid @RequestBody InboundSaveRequest request) {
        return R.ok(inboundService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("purchase:inbound:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody InboundSaveRequest request) {
        inboundService.update(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/audit")
    @SaCheckPermission("purchase:inbound:audit")
    public R<Void> audit(@PathVariable Long id) {
        inboundService.audit(id);
        return R.ok();
    }

    @PostMapping("/{id}/void")
    @SaCheckPermission("purchase:inbound:void")
    public R<Void> voidInbound(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        inboundService.voidInbound(id, request.getReason());
        return R.ok();
    }

    @org.springframework.web.bind.annotation.GetMapping
    @SaCheckPermission("purchase:inbound:list")
    public R<com.wille.erp.common.api.PageResult<com.wille.erp.purchase.entity.PurInbound>> page(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int pageNum,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int pageSize,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String keyword) {
        return R.ok(inboundService.page(pageNum, pageSize, keyword));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    @SaCheckPermission("purchase:inbound:list")
    public R<com.wille.erp.purchase.dto.InboundVO> get(@PathVariable Long id) {
        return R.ok(inboundService.get(id));
    }

    /** spec §5：从入库单发起退货——按可退余量生成退货草稿（M4 审查 I7，权限点 1116） */
    @PostMapping("/{id}/create-return")
    @SaCheckPermission("purchase:inbound:create-return")
    public R<Long> createReturn(@PathVariable Long id) {
        return R.ok(returnService.createFromInbound(id));
    }
}
