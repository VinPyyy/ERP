package com.wille.erp.basedata.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.basedata.dto.OptionVOs;
import com.wille.erp.basedata.dto.WarehouseSaveRequest;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.basedata.service.WarehouseService;
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
 * 仓库 ≤10：全量列表不分页（用户绑仓/单据选仓的下拉数据源）。
 */
@RestController
@RequestMapping("/api/basedata/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    /** 下拉引用数据：启用仓库裁剪视图，登录即可（docs/system/reference-options.md） */
    @SaCheckLogin
    @GetMapping("/options")
    public R<List<OptionVOs.WarehouseOption>> options() {
        return R.ok(warehouseService.listAll().stream()
                .filter(w -> w.getStatus() == null || w.getStatus() == 1)
                .map(w -> new OptionVOs.WarehouseOption(w.getId(), w.getCode(), w.getName()))
                .toList());
    }

    @GetMapping
    @SaCheckPermission("basedata:warehouse:list")
    public R<List<BaseWarehouse>> list() {
        return R.ok(warehouseService.listAll());
    }

    @PostMapping
    @SaCheckPermission("basedata:warehouse:create")
    public R<Long> create(@Valid @RequestBody WarehouseSaveRequest request) {
        return R.ok(warehouseService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("basedata:warehouse:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody WarehouseSaveRequest request) {
        warehouseService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("basedata:warehouse:delete")
    public R<Void> delete(@PathVariable Long id) {
        warehouseService.delete(id);
        return R.ok();
    }
}
