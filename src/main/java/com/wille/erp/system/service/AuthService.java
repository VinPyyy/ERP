package com.wille.erp.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.system.dto.LoginRequest;
import com.wille.erp.system.dto.LoginResponse;
import com.wille.erp.system.dto.MeResponse;
import com.wille.erp.system.entity.SysUser;
import com.wille.erp.system.mapper.SysPermissionMapper;
import com.wille.erp.system.mapper.SysRoleMapper;
import com.wille.erp.system.mapper.SysUserMapper;
import com.wille.erp.system.mapper.SysUserWarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务：登录（BCrypt 校验 + Sa-Token 内存会话）、me（角色/权限码并集 + 可见仓库聚合）、登出。
 * 登录失败统一"用户名或密码错误"，不泄露用户是否存在。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserWarehouseMapper userWarehouseMapper;
    private final PasswordEncoder passwordEncoder;

    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.USER_DISABLED, "用户已停用，请联系管理员");
        }

        StpUtil.login(user.getId());
        StpUtil.getSession().set(StpCurrentUserHolder.SESSION_USERNAME, user.getUsername());

        SysUser touch = new SysUser();
        touch.setId(user.getId());
        touch.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(touch);

        return new LoginResponse(StpUtil.getTokenValue());
    }

    public MeResponse me() {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_LOGIN, "未登录");
        }
        return MeResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .roles(roleMapper.selectRoleCodesByUserId(userId))
                .perms(permissionMapper.selectPermCodesByUserId(userId))
                .warehouses(userWarehouseMapper.selectWarehouseIdsByUserId(userId))
                .build();
    }

    public void logout() {
        StpUtil.logout();
    }
}
