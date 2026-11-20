package com.wille.erp.purchase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.purchase.dto.ReturnSaveRequest;
import com.wille.erp.purchase.dto.ReturnVO;
import com.wille.erp.purchase.entity.PurReturn;
import com.wille.erp.purchase.service.ReturnService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchase/returns")
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnService returnService;

    @Data
    public static class VoidRequest {
        @NotBlank(message = "作废原因必填")
        private String reason;
    }

    @PostMapping
    @SaCheckPermission("purchase:return:create")
    public R<Long> create(@Valid @RequestBody ReturnSaveRequest request) {
        return R.ok(returnService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("purchase:return:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ReturnSaveRequest request) {
        returnService.update(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/audit")
    @SaCheckPermission("purchase:return:audit")
    public R<Void> audit(@PathVariable Long id) {
        returnService.audit(id);
        return R.ok();
    }

    @PostMapping("/{id}/void")
    @SaCheckPermission("purchase:return:void")
    public R<Void> voidReturn(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        returnService.voidReturn(id, request.getReason());
        return R.ok();
    }

    @GetMapping
    @SaCheckPermission("purchase:return:list")
    public R<PageResult<PurReturn>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        return R.ok(returnService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("purchase:return:list")
    public R<ReturnVO> get(@PathVariable Long id) {
        return R.ok(returnService.get(id));
    }
}
