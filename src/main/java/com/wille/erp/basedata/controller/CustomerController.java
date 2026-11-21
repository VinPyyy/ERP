package com.wille.erp.basedata.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.ImportResultVO;
import com.wille.erp.basedata.dto.OptionVOs;
import com.wille.erp.basedata.dto.PartnerQuery;
import com.wille.erp.basedata.dto.PartnerSaveRequest;
import com.wille.erp.basedata.entity.BaseCustomer;
import com.wille.erp.basedata.excel.PartnerExcelService;
import com.wille.erp.basedata.service.CustomerService;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import jakarta.validation.Valid;

import java.util.List;
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
@RequestMapping("/api/basedata/customers")
@RequiredArgsConstructor
public class CustomerController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CustomerService customerService;
    private final PartnerExcelService partnerExcelService;

    /** 下拉引用数据：启用客户裁剪视图，登录即可（docs/system/reference-options.md） */
    @SaCheckLogin
    @GetMapping("/options")
    public R<List<OptionVOs.PartnerOption>> options() {
        return R.ok(customerService.listAllActive().stream()
                .map(c -> new OptionVOs.PartnerOption(c.getId(), c.getCode(), c.getName()))
                .toList());
    }

    @GetMapping
    @SaCheckPermission("basedata:customer:list")
    public R<PageResult<BaseCustomer>> page(PartnerQuery query) {
        return R.ok(customerService.page(query));
    }

    @PostMapping
    @SaCheckPermission("basedata:customer:create")
    public R<Long> create(@Valid @RequestBody PartnerSaveRequest request) {
        return R.ok(customerService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("basedata:customer:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PartnerSaveRequest request) {
        customerService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("basedata:customer:delete")
    public R<Void> delete(@PathVariable Long id) {
        customerService.delete(id);
        return R.ok();
    }

    @PostMapping("/import")
    @SaCheckPermission("basedata:customer:import")
    public R<ImportResultVO> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        return R.ok(partnerExcelService.importCustomers(file.getInputStream()));
    }

    @GetMapping("/export")
    @SaCheckPermission("basedata:customer:export")
    public ResponseEntity<byte[]> export(@ModelAttribute PartnerQuery query) {
        return ResponseEntity.ok().contentType(XLSX)
                .header("Content-Disposition", "attachment; filename=customers.xlsx")
                .body(partnerExcelService.exportCustomers(query));
    }
}
