package com.wille.erp.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.R;
import com.wille.erp.system.dto.RoleOptionVO;
import com.wille.erp.system.dto.RoleSaveRequest;
import com.wille.erp.system.entity.SysRole;
import com.wille.erp.system.service.PermissionService;
import com.wille.erp.system.service.RoleService;
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

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SysRoleController {

    private final RoleService roleService;
    private final PermissionService permissionService;

    /** 下拉引用数据：登录即可（docs/system/reference-options.md），不含备注/审计字段 */
    @SaCheckLogin
    @GetMapping("/roles/options")
    public R<List<RoleOptionVO>> roleOptions() {
        return R.ok(roleService.list().stream()
                .map(r -> new RoleOptionVO(r.getId(), r.getRoleCode(), r.getRoleName()))
                .toList());
    }

    @GetMapping("/roles")
    @SaCheckPermission("system:role:list")
    public R<List<SysRole>> list() {
        return R.ok(roleService.list());
    }

    @PostMapping("/roles")
    @SaCheckPermission("system:role:create")
    public R<Long> create(@Valid @RequestBody RoleSaveRequest request) {
        return R.ok(roleService.create(request));
    }

    @PutMapping("/roles/{id}")
    @SaCheckPermission("system:role:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody RoleSaveRequest request) {
        roleService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/roles/{id}")
    @SaCheckPermission("system:role:delete")
    public R<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return R.ok();
    }

    @GetMapping("/roles/{id}/permissions")
    @SaCheckPermission("system:role:list")
    public R<List<Long>> permissionIds(@PathVariable Long id) {
        return R.ok(roleService.permissionIds(id));
    }

    /** 授权全量替换 */
    @PutMapping("/roles/{id}/permissions")
    @SaCheckPermission("system:role:grant")
    public R<Void> grant(@PathVariable Long id, @RequestBody List<Long> permissionIds) {
        roleService.grantPermissions(id, permissionIds);
        return R.ok();
    }

    @GetMapping("/permissions/tree")
    @SaCheckPermission("system:role:list")
    public R<?> tree() {
        return R.ok(permissionService.tree());
    }
}
