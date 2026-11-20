package com.wille.erp.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.system.dto.ResetPasswordRequest;
import com.wille.erp.system.dto.UserQuery;
import com.wille.erp.system.dto.UserSaveRequest;
import com.wille.erp.system.dto.UserVO;
import com.wille.erp.system.service.UserService;
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

@RestController
@RequestMapping("/api/system/users")
@RequiredArgsConstructor
public class SysUserController {

    private final UserService userService;

    @GetMapping
    @SaCheckPermission("system:user:list")
    public R<PageResult<UserVO>> page(UserQuery query) {
        return R.ok(userService.page(query));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("system:user:list")
    public R<UserVO> get(@PathVariable Long id) {
        return R.ok(userService.get(id));
    }

    @PostMapping
    @SaCheckPermission("system:user:create")
    public R<Long> create(@Valid @RequestBody UserSaveRequest request) {
        return R.ok(userService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("system:user:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UserSaveRequest request) {
        userService.update(id, request);
        return R.ok();
    }

    @PutMapping("/{id}/password")
    @SaCheckPermission("system:user:reset-password")
    public R<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.getPassword());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:user:delete")
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.ok();
    }
}
