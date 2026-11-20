package com.wille.erp.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.wille.erp.system.api.CurrentUserHolder;
import com.wille.erp.system.entity.SysUserWarehouse;
import com.wille.erp.system.mapper.SysUserWarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CurrentUserHolder 的 Sa-Token 实现：操作人取自会话，权限走 StpUtil，
 * 可见仓库实时查库（绑定变更即时生效；中小规模每请求一两次查询可接受）。
 */
@Component
@RequiredArgsConstructor
public class StpCurrentUserHolder implements CurrentUserHolder {

    /** 登录时写入 SaSession 的用户名键 */
    public static final String SESSION_USERNAME = "username";

    private final SysUserWarehouseMapper userWarehouseMapper;

    @Override
    public Long userId() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        return loginId == null ? null : Long.valueOf(loginId.toString());
    }

    @Override
    public String username() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        Object username = StpUtil.getSession().get(SESSION_USERNAME);
        return username == null ? null : username.toString();
    }

    @Override
    public boolean hasPerm(String permCode) {
        return StpUtil.isLogin() && StpUtil.hasPermission(permCode);
    }

    @Override
    public Set<Long> visibleWarehouseIds() {
        Long userId = userId();
        if (userId == null) {
            return Set.of();
        }
        List<Long> ids = userWarehouseMapper.selectWarehouseIdsByUserId(userId);
        return new LinkedHashSet<>(ids);
    }

    @Override
    public boolean allWarehouses() {
        return visibleWarehouseIds().contains(SysUserWarehouse.ALL_WAREHOUSES);
    }
}
