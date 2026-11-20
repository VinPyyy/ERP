package com.wille.erp.basedata.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.ImportResultVO;
import com.wille.erp.basedata.dto.OptionVOs;
import com.wille.erp.basedata.dto.ProductQuery;
import com.wille.erp.basedata.dto.ProductSaveRequest;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.excel.ProductExcelService;
import com.wille.erp.basedata.service.ProductService;
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

import java.util.List;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/basedata/products")
@RequiredArgsConstructor
public class ProductController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ProductService productService;
    private final ProductExcelService productExcelService;

    /** 下拉引用数据：启用商品裁剪视图（不含价格成本），登录即可（docs/system/reference-options.md） */
    @SaCheckLogin
    @GetMapping("/options")
    public R<List<OptionVOs.ProductOption>> options() {
        return R.ok(productService.listAllActive());
    }

    @GetMapping
    @SaCheckPermission("basedata:product:list")
    public R<PageResult<BaseProduct>> page(ProductQuery query) {
        return R.ok(productService.page(query));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("basedata:product:list")
    public R<BaseProduct> get(@PathVariable Long id) {
        return R.ok(productService.get(id));
    }

    @PostMapping
    @SaCheckPermission("basedata:product:create")
    public R<Long> create(@Valid @RequestBody ProductSaveRequest request) {
        return R.ok(productService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("basedata:product:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ProductSaveRequest request) {
        productService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("basedata:product:delete")
    public R<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return R.ok();
    }

    /** Excel 导入：全量校验，有错整批不落库并返回行号清单 */
    @PostMapping("/import")
    @SaCheckPermission("basedata:product:import")
    public R<ImportResultVO> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        return R.ok(productExcelService.importProducts(file.getInputStream()));
    }

    @GetMapping("/export")
    @SaCheckPermission("basedata:product:export")
    public ResponseEntity<byte[]> export(@ModelAttribute ProductQuery query) {
        byte[] bytes = productExcelService.export(query);
        String filename = "products-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header("Content-Disposition", "attachment; filename=" + filename)
                .body(bytes);
    }
}
