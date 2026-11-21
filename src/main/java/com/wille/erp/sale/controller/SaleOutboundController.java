package com.wille.erp.sale.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.sale.dto.OutboundSaveRequest;
import com.wille.erp.sale.dto.SaleDocOptionVO;
import com.wille.erp.sale.dto.OutboundVO;
import com.wille.erp.sale.entity.SalOutbound;
import com.wille.erp.sale.service.SaleOutboundService;
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
@RequestMapping("/api/sale/outbounds")
@RequiredArgsConstructor
public class SaleOutboundController {

    private final SaleOutboundService outboundService;

    @Data
    public static class VoidRequest {
        @NotBlank(message = "作废原因必填")
        private String reason;
    }

    @PostMapping
    @SaCheckPermission("sale:outbound:create")
    public R<Long> create(@Valid @RequestBody OutboundSaveRequest request) {
        return R.ok(outboundService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("sale:outbound:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody OutboundSaveRequest request) {
        outboundService.update(id, request);
        return R.ok();
    }

    /** 退货源单下拉：已审核出库单；仅放行能建退货的角色 */
    @GetMapping("/options")
    @SaCheckPermission("sale:return:create")
    public R<List<SaleDocOptionVO>> options() {
        return R.ok(outboundService.auditedOptions());
    }

    @GetMapping
    @SaCheckPermission("sale:outbound:list")
    public R<PageResult<SalOutbound>> page(@RequestParam(defaultValue = "1") int pageNum,
                                           @RequestParam(defaultValue = "20") int pageSize,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(outboundService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("sale:outbound:list")
    public R<OutboundVO> get(@PathVariable Long id) {
        return R.ok(outboundService.get(id));
    }

    @PostMapping("/{id}/audit")
    @SaCheckPermission("sale:outbound:audit")
    public R<Void> audit(@PathVariable Long id) {
        outboundService.audit(id);
        return R.ok();
    }

    @PostMapping("/{id}/void")
    @SaCheckPermission("sale:outbound:void")
    public R<Void> voidOutbound(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        outboundService.voidOutbound(id, request.getReason());
        return R.ok();
    }
}
