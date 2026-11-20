package com.wille.erp.basedata.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.basedata.dto.CategorySaveRequest;
import com.wille.erp.basedata.entity.BaseCategory;
import com.wille.erp.basedata.service.CategoryService;
import com.wille.erp.common.api.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 类别是商品管理的附件：沿用商品权限点（list/edit），不另设授权。
 */
@RestController
@RequestMapping("/api/basedata/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @SaCheckPermission("basedata:product:list")
    public R<List<BaseCategory>> list() {
        return R.ok(categoryService.listAll());
    }

    @PostMapping
    @SaCheckPermission("basedata:product:edit")
    public R<Long> create(@Valid @RequestBody CategorySaveRequest request) {
        return R.ok(categoryService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("basedata:product:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody CategorySaveRequest request) {
        categoryService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("basedata:product:edit")
    public R<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return R.ok();
    }
}
