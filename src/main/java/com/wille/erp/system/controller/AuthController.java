package com.wille.erp.system.controller;

import com.wille.erp.common.api.R;
import com.wille.erp.system.dto.LoginRequest;
import com.wille.erp.system.dto.LoginResponse;
import com.wille.erp.system.dto.MeResponse;
import com.wille.erp.system.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点（spec §5.3）：login 在 SaTokenConfig 白名单，其余 /api/** 走 Sa-Token 登录态。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    /** 当前用户 + 权限码集合 + 可见仓库（前端路由守卫/菜单过滤/按钮权限的数据源） */
    @GetMapping("/me")
    public R<MeResponse> me() {
        return R.ok(authService.me());
    }
}
