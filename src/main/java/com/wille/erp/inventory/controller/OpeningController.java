package com.wille.erp.inventory.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.ImportResultVO;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.inventory.dto.OpeningSaveRequest;
import com.wille.erp.inventory.dto.OpeningVO;
import com.wille.erp.inventory.excel.OpeningExcelService;
import com.wille.erp.inventory.service.OpeningService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 期初库存单端点（spec §5；编辑草稿复用 create 权限点——V2 种子无独立 edit 点，账本裁决） */
@RestController
@RequestMapping("/api/inventory/openings")
@RequiredArgsConstructor
public class OpeningController {

    private final OpeningService openingService;
    private final OpeningExcelService excelService;

    @Data
    public static class VoidRequest {
        @NotBlank(message = "作废原因必填")
        private String reason;
    }

    @PostMapping
    @SaCheckPermission("inventory:opening:create")
    public R<Long> create(@Valid @RequestBody OpeningSaveRequest request) {
        return R.ok(openingService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("inventory:opening:create")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody OpeningSaveRequest request) {
        openingService.update(id, request);
        return R.ok();
    }

    @GetMapping
    @SaCheckPermission("inventory:opening:list")
    public R<PageResult<OpeningVO>> page(@RequestParam(defaultValue = "1") int pageNum,
                                         @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(openingService.page(pageNum, pageSize));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("inventory:opening:list")
    public R<OpeningVO> get(@PathVariable Long id) {
        return R.ok(openingService.get(id));
    }

    @PostMapping("/{id}/audit")
    @SaCheckPermission("inventory:opening:audit")
    public R<Void> audit(@PathVariable Long id) {
        openingService.audit(id);
        return R.ok();
    }

    @PostMapping("/{id}/void")
    @SaCheckPermission("inventory:opening:void")
    public R<Void> voidOpening(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        openingService.voidOpening(id, request.getReason());
        return R.ok();
    }

    /** Excel 导入草稿行（全量校验，有错整批不落） */
    @PostMapping("/{id}/import")
    @SaCheckPermission("inventory:opening:import")
    public R<ImportResultVO> importExcel(@PathVariable Long id, @RequestParam("file") MultipartFile file)
            throws IOException {
        return R.ok(excelService.importToDraft(id, file.getInputStream()));
    }

    @GetMapping("/template")
    @SaCheckPermission("inventory:opening:import")
    public void template(HttpServletResponse response) throws IOException {
        byte[] xlsx = excelService.template();
        String filename = URLEncoder.encode("期初库存导入模板.xlsx", StandardCharsets.UTF_8);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename);
        response.setContentLength(xlsx.length);
        response.getOutputStream().write(xlsx);
        response.flushBuffer();
    }
}
