package com.wille.erp.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.system.dto.RoleSaveRequest;
import com.wille.erp.system.entity.SysRole;
import com.wille.erp.system.entity.SysRolePermission;
import com.wille.erp.system.mapper.SysRoleMapper;
import com.wille.erp.system.mapper.SysRolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色管理：CRUD + 授权全量替换（中间表物理删重插，规避 UNIQUE 逻辑删冲突）。
 * V2 预置五角色（built_in=1）不可删。
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;

    @Transactional(rollbackFor = Exception.class)
    public Long create(RoleSaveRequest request) {
        requireRoleCodeUnique(request.getRoleCode(), null);
        SysRole role = new SysRole();
        role.setRoleCode(request.getRoleCode());
        role.setRoleName(request.getRoleName());
        role.setRemark(request.getRemark());
        role.setBuiltIn(0);
        roleMapper.insert(role);
        return role.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, RoleSaveRequest request) {
        SysRole role = requireRole(id);
        requireRoleCodeUnique(request.getRoleCode(), id);
        role.setRoleCode(request.getRoleCode());
        role.setRoleName(request.getRoleName());
        role.setRemark(request.getRemark());
        roleMapper.updateById(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysRole role = requireRole(id);
        if (role.getBuiltIn() != null && role.getBuiltIn() == 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "预置角色不可删除");
        }
        roleMapper.deleteById(id);
    }

    public List<SysRole> list() {
        return roleMapper.selectList(Wrappers.<SysRole>lambdaQuery().orderByAsc(SysRole::getId));
    }

    public List<Long> permissionIds(Long roleId) {
        requireRole(roleId);
        return rolePermissionMapper.selectList(Wrappers.<SysRolePermission>lambdaQuery()
                        .eq(SysRolePermission::getRoleId, roleId)).stream()
                .map(SysRolePermission::getPermissionId)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void grantPermissions(Long roleId, List<Long> permissionIds) {
        requireRole(roleId);
        rolePermissionMapper.deleteHardByRoleId(roleId);
        for (Long permissionId : permissionIds == null ? List.<Long>of() : permissionIds) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permissionId);
            rolePermissionMapper.insert(rp);
        }
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色不存在");
        }
        return role;
    }

    private void requireRoleCodeUnique(String roleCode, Long excludeId) {
        Long count = roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, roleCode)
                .ne(excludeId != null, SysRole::getId, excludeId));
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色编码已存在: " + roleCode);
        }
    }
}
