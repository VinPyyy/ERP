package com.wille.erp.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.system.dto.UserQuery;
import com.wille.erp.system.dto.UserSaveRequest;
import com.wille.erp.system.dto.UserVO;
import com.wille.erp.system.entity.SysRole;
import com.wille.erp.system.entity.SysUser;
import com.wille.erp.system.entity.SysUserWarehouse;
import com.wille.erp.system.entity.SysUserRole;
import com.wille.erp.system.mapper.SysRoleMapper;
import com.wille.erp.system.mapper.SysUserMapper;
import com.wille.erp.system.mapper.SysUserWarehouseMapper;
import com.wille.erp.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户管理：CRUD（逻辑删）+ 角色分配 + 仓库绑定（全量替换，中间表物理删重插）+ 重置密码。
 * 密码 BCrypt 存储；VO 永不含密码字段；内置 admin 账号不可删。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String ADMIN_USERNAME = "admin";

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserWarehouseMapper userWarehouseMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional(rollbackFor = Exception.class)
    public Long create(UserSaveRequest request) {
        requireUsernameUnique(request.getUsername(), null);
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新建用户必须设置密码");
        }
        SysUser user = new SysUser();
        applyBasicFields(user, request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        userMapper.insert(user);
        replaceRoles(user.getId(), request.getRoleIds());
        replaceWarehouses(user.getId(), request.getWarehouseIds());
        return user.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UserSaveRequest request) {
        SysUser user = requireUser(id);
        requireUsernameUnique(request.getUsername(), id);
        applyBasicFields(user, request);
        userMapper.updateById(user);
        replaceRoles(id, request.getRoleIds());
        replaceWarehouses(id, request.getWarehouseIds());
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String newRawPassword) {
        SysUser user = requireUser(id);
        if (newRawPassword == null || newRawPassword.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码不能为空");
        }
        user.setPassword(passwordEncoder.encode(newRawPassword));
        userMapper.updateById(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysUser user = requireUser(id);
        if (ADMIN_USERNAME.equals(user.getUsername())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "内置管理员账号不可删除");
        }
        userMapper.deleteById(id);
    }

    public UserVO get(Long id) {
        SysUser user = requireUser(id);
        return assemble(List.of(user)).get(0);
    }

    public PageResult<UserVO> page(UserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery()
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(SysUser::getUsername, query.getKeyword())
                                .or().like(SysUser::getRealName, query.getKeyword()))
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
                .orderByDesc(SysUser::getCreateTime);
        Page<SysUser> page = userMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        List<UserVO> records = assemble(page.getRecords());
        return new PageResult<>(page.getTotal(), records);
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户不存在");
        }
        return user;
    }

    private void requireUsernameUnique(String username, Long excludeId) {
        Long count = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)
                .ne(excludeId != null, SysUser::getId, excludeId));
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名已存在: " + username);
        }
    }

    private void applyBasicFields(SysUser user, UserSaveRequest request) {
        user.setUsername(request.getUsername());
        user.setRealName(request.getRealName());
        user.setPhone(request.getPhone());
        user.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        user.setRemark(request.getRemark());
    }

    private void replaceRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.deleteHardByUserId(userId);
        for (Long roleId : roleIds == null ? List.<Long>of() : roleIds) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
    }

    private void replaceWarehouses(Long userId, List<Long> warehouseIds) {
        userWarehouseMapper.deleteHardByUserId(userId);
        for (Long warehouseId : warehouseIds == null ? List.<Long>of() : warehouseIds) {
            SysUserWarehouse userWarehouse = new SysUserWarehouse();
            userWarehouse.setUserId(userId);
            userWarehouse.setWarehouseId(warehouseId);
            userWarehouseMapper.insert(userWarehouse);
        }
    }

    /** 批量装配角色/仓库信息，避免逐行 N+1 */
    private List<UserVO> assemble(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = users.stream().map(SysUser::getId).toList();

        Map<Long, List<Long>> rolesByUser = userRoleMapper.selectList(
                        Wrappers.<SysUserRole>lambdaQuery().in(SysUserRole::getUserId, userIds)).stream()
                .collect(Collectors.groupingBy(SysUserRole::getUserId,
                        Collectors.mapping(SysUserRole::getRoleId, Collectors.toList())));

        List<Long> allRoleIds = rolesByUser.values().stream().flatMap(List::stream).distinct().toList();
        Map<Long, SysRole> roleById = allRoleIds.isEmpty()
                ? Map.of()
                : roleMapper.selectByIds(allRoleIds).stream()
                        .collect(Collectors.toMap(SysRole::getId, Function.identity()));

        Map<Long, List<Long>> warehousesByUser = userWarehouseMapper.selectList(
                        Wrappers.<SysUserWarehouse>lambdaQuery().in(SysUserWarehouse::getUserId, userIds)).stream()
                .collect(Collectors.groupingBy(SysUserWarehouse::getUserId,
                        Collectors.mapping(SysUserWarehouse::getWarehouseId, Collectors.toList())));

        return users.stream().map(user -> {
            UserVO vo = new UserVO();
            vo.setId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setRealName(user.getRealName());
            vo.setPhone(user.getPhone());
            vo.setStatus(user.getStatus());
            vo.setLastLoginTime(user.getLastLoginTime());
            vo.setCreateTime(user.getCreateTime());
            vo.setRemark(user.getRemark());
            List<Long> roleIds = rolesByUser.getOrDefault(user.getId(), Collections.emptyList());
            vo.setRoleIds(roleIds);
            vo.setRoleNames(roleIds.stream()
                    .map(roleById::get)
                    .filter(Objects::nonNull)
                    .map(SysRole::getRoleName)
                    .toList());
            vo.setWarehouseIds(warehousesByUser.getOrDefault(user.getId(), Collections.emptyList()));
            return vo;
        }).toList();
    }
}
