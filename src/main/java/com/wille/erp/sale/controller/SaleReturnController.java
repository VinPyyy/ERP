package com.wille.erp.sale.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.sale.dto.ReturnSaveRequest;
import com.wille.erp.sale.dto.ReturnVO;
import com.wille.erp.sale.entity.SalReturn;
import com.wille.erp.sale.service.SaleReturnService;
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
@RequestMapping("/api/sale")
@RequiredArgsConstructor
public class SaleReturnController {

    private final SaleReturnService returnService;

    @Data
    public static class VoidRequest {
        @NotBlank(message = "作废原因必填")
        private String reason;
    }

    @PostMapping("/returns")
    @SaCheckPermission("sale:return:create")
    public R<Long> create(@Valid @RequestBody ReturnSaveRequest request) {
        return R.ok(returnService.create(request));
    }

    @PutMapping("/returns/{id}")
    @SaCheckPermission("sale:return:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ReturnSaveRequest request) {
        returnService.update(id, request);
        return R.ok();
    }

    @GetMapping("/returns")
    @SaCheckPermission("sale:return:list")
    public R<PageResult<SalReturn>> page(@RequestParam(defaultValue = "1") int pageNum,
                                         @RequestParam(defaultValue = "20") int pageSize,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(returnService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/returns/{id}")
    @SaCheckPermission("sale:return:list")
    public R<ReturnVO> get(@PathVariable Long id) {
        return R.ok(returnService.get(id));
    }

    @PostMapping("/returns/{id}/audit")
    @SaCheckPermission("sale:return:audit")
    public R<Void> audit(@PathVariable Long id) {
        returnService.audit(id);
        return R.ok();
    }

    @PostMapping("/returns/{id}/void")
    @SaCheckPermission("sale:return:void")
    public R<Void> voidReturn(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        returnService.voidReturn(id, request.getReason());
        return R.ok();
    }

    /** spec §5：原出库行可退 SN（§8-3） */
    @GetMapping("/outbound-items/{itemId}/returnable-sns")
    @SaCheckPermission("sale:return:create")
    public R<List<String>> returnableSns(@PathVariable Long itemId) {
        return R.ok(returnService.returnableSns(itemId));
    }

    /** spec §5：从出库单发起退货（可退余量草稿，权限点 1216） */
    @PostMapping("/outbounds/{id}/create-return")
    @SaCheckPermission("sale:outbound:create-return")
    public R<Long> createReturn(@PathVariable Long id) {
        return R.ok(returnService.createFromOutbound(id));
    }
}
