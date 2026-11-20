package com.wille.erp.system.service;

import cn.dev33.satoken.stp.StpInterface;
import com.wille.erp.system.mapper.SysPermissionMapper;
import com.wille.erp.system.mapper.SysRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限数据源：@SaCheckPermission / @SaCheckRole 校验时回调。
 * 权限码 = 用户所有角色的权限点并集（菜单 + 按钮 + 成本可见性）。
 */
@Component
@RequiredArgsConstructor
public class SaPermissionImpl implements StpInterface {

    private final SysPermissionMapper permissionMapper;
    private final SysRoleMapper roleMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return permissionMapper.selectPermCodesByUserId(Long.valueOf(loginId.toString()));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return roleMapper.selectRoleCodesByUserId(Long.valueOf(loginId.toString()));
    }
}
