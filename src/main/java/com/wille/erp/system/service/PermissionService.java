package com.wille.erp.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.system.dto.PermissionNode;
import com.wille.erp.system.entity.SysPermission;
import com.wille.erp.system.mapper.SysPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限点全量树（角色授权界面用）。id 升序保证 V2 种子顺序稳定。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final SysPermissionMapper permissionMapper;

    public List<PermissionNode> tree() {
        List<SysPermission> all = permissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery().orderByAsc(SysPermission::getId));

        Map<Long, PermissionNode> nodes = new LinkedHashMap<>();
        for (SysPermission permission : all) {
            nodes.put(permission.getId(), toNode(permission));
        }

        List<PermissionNode> roots = new ArrayList<>();
        for (PermissionNode node : nodes.values()) {
            PermissionNode parent = node.getParentId() == null ? null : nodes.get(node.getParentId());
            if (parent == null || node.getParentId() == 0L) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    private PermissionNode toNode(SysPermission permission) {
        PermissionNode node = new PermissionNode();
        node.setId(permission.getId());
        node.setPermCode(permission.getPermCode());
        node.setPermName(permission.getPermName());
        node.setType(permission.getType());
        node.setParentId(permission.getParentId());
        node.setRoutePath(permission.getRoutePath());
        node.setIcon(permission.getIcon());
        node.setSortNo(permission.getSortNo());
        return node;
    }
}
