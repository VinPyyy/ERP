package com.wille.erp.basedata.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.ImportResultVO;
import com.wille.erp.basedata.dto.PartnerQuery;
import com.wille.erp.basedata.dto.PartnerSaveRequest;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.excel.PartnerExcelService;
import com.wille.erp.basedata.service.SupplierService;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/basedata/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final SupplierService supplierService;
    private final PartnerExcelService partnerExcelService;

    @GetMapping
    @SaCheckPermission("basedata:supplier:list")
    public R<PageResult<BaseSupplier>> page(PartnerQuery query) {
        return R.ok(supplierService.page(query));
    }

    @PostMapping
    @SaCheckPermission("basedata:supplier:create")
    public R<Long> create(@Valid @RequestBody PartnerSaveRequest request) {
        return R.ok(supplierService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("basedata:supplier:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PartnerSaveRequest request) {
        supplierService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("basedata:supplier:delete")
    public R<Void> delete(@PathVariable Long id) {
        supplierService.delete(id);
        return R.ok();
    }

    @PostMapping("/import")
    @SaCheckPermission("basedata:supplier:import")
    public R<ImportResultVO> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        return R.ok(partnerExcelService.importSuppliers(file.getInputStream()));
    }

    @GetMapping("/export")
    @SaCheckPermission("basedata:supplier:export")
    public ResponseEntity<byte[]> export(@ModelAttribute PartnerQuery query) {
        return ResponseEntity.ok().contentType(XLSX)
                .header("Content-Disposition", "attachment; filename=suppliers.xlsx")
                .body(partnerExcelService.exportSuppliers(query));
    }
}
