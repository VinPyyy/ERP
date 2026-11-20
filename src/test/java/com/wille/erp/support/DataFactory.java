package com.wille.erp.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseCategory;
import com.wille.erp.basedata.entity.BaseCustomer;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.basedata.mapper.BaseCategoryMapper;
import com.wille.erp.basedata.mapper.BaseCustomerMapper;
import com.wille.erp.basedata.mapper.BaseProductMapper;
import com.wille.erp.basedata.mapper.BaseSupplierMapper;
import com.wille.erp.basedata.mapper.BaseWarehouseMapper;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.system.entity.SysPermission;
import com.wille.erp.system.entity.SysRole;
import com.wille.erp.system.entity.SysRolePermission;
import com.wille.erp.system.entity.SysUser;
import com.wille.erp.system.entity.SysUserWarehouse;
import com.wille.erp.system.entity.SysUserRole;
import com.wille.erp.system.mapper.SysPermissionMapper;
import com.wille.erp.system.mapper.SysRoleMapper;
import com.wille.erp.system.mapper.SysRolePermissionMapper;
import com.wille.erp.system.mapper.SysUserMapper;
import com.wille.erp.system.mapper.SysUserWarehouseMapper;
import com.wille.erp.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 测试数据工厂：一行代码造用户/角色/授权/仓库绑定。
 * 权限点引用 V2 种子的 perm_code（不新建权限点）。
 */
@Component
@RequiredArgsConstructor
public class DataFactory {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserWarehouseMapper userWarehouseMapper;
    private final BaseProductMapper productMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BaseCustomerMapper customerMapper;
    private final BaseSupplierMapper supplierMapper;
    private final BaseCategoryMapper categoryMapper;
    private final PasswordEncoder passwordEncoder;

    // ---------- M2 基础资料造数 ----------

    public BaseProduct product(String skuCode, MgmtMode mgmtMode) {
        BaseProduct product = new BaseProduct();
        product.setSkuCode(skuCode);
        product.setName("商品-" + skuCode);
        product.setUnit("个");
        product.setMgmtMode(mgmtMode);
        product.setShelfLifeDays(mgmtMode.lotManaged() ? 365 : null);
        product.setStatus(1);
        productMapper.insert(product);
        return product;
    }

    public BaseWarehouse warehouse(String code) {
        BaseWarehouse warehouse = new BaseWarehouse();
        warehouse.setCode(code);
        warehouse.setName("仓库-" + code);
        warehouse.setStatus(1);
        warehouse.setSortNo(0);
        warehouseMapper.insert(warehouse);
        return warehouse;
    }

    public BaseCustomer customer(String code) {
        BaseCustomer customer = new BaseCustomer();
        customer.setCode(code);
        customer.setName("客户-" + code);
        customer.setStatus(1);
        customerMapper.insert(customer);
        return customer;
    }

    public BaseSupplier supplier(String code) {
        BaseSupplier supplier = new BaseSupplier();
        supplier.setCode(code);
        supplier.setName("供应商-" + code);
        supplier.setStatus(1);
        supplierMapper.insert(supplier);
        return supplier;
    }

    public BaseCategory category(String name) {
        BaseCategory category = new BaseCategory();
        category.setName(name);
        category.setParentId(0L);
        category.setSortNo(0);
        categoryMapper.insert(category);
        return category;
    }

    /** 造启用用户并绑角色（角色须已存在——V2 预置或 roleWithPerms 创建） */
    public SysUser user(String username, String rawPassword, String... roleCodes) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRealName(username);
        user.setStatus(1);
        userMapper.insert(user);
        for (String roleCode : roleCodes) {
            SysRole role = roleMapper.selectOne(
                    Wrappers.<SysRole>lambdaQuery().eq(SysRole::getRoleCode, roleCode));
            if (role == null) {
                throw new IllegalArgumentException("角色不存在: " + roleCode);
            }
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(role.getId());
            userRoleMapper.insert(userRole);
        }
        return user;
    }

    /** 造停用用户 */
    public SysUser disabledUser(String username, String rawPassword, String... roleCodes) {
        SysUser user = user(username, rawPassword, roleCodes);
        user.setStatus(0);
        userMapper.updateById(user);
        return user;
    }

    /** 造自定义角色并按 V2 种子的 perm_code 授权 */
    public SysRole roleWithPerms(String roleCode, String roleName, String... permCodes) {
        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setBuiltIn(0);
        roleMapper.insert(role);
        grantPerms(roleCode, permCodes);
        return role;
    }

    public void grantPerms(String roleCode, String... permCodes) {
        SysRole role = roleMapper.selectOne(
                Wrappers.<SysRole>lambdaQuery().eq(SysRole::getRoleCode, roleCode));
        for (String permCode : permCodes) {
            List<SysPermission> permissions = permissionMapper.selectList(
                    Wrappers.<SysPermission>lambdaQuery().eq(SysPermission::getPermCode, permCode));
            if (permissions.isEmpty()) {
                throw new IllegalArgumentException("权限点不在 V2 种子中: " + permCode);
            }
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(role.getId());
            rp.setPermissionId(permissions.get(0).getId());
            rolePermissionMapper.insert(rp);
        }
    }

    /** 绑定可见仓库；传 0 表示全部仓库 */
    public void bindWarehouses(Long userId, long... warehouseIds) {
        for (long warehouseId : warehouseIds) {
            SysUserWarehouse uw = new SysUserWarehouse();
            uw.setUserId(userId);
            uw.setWarehouseId(warehouseId);
            userWarehouseMapper.insert(uw);
        }
    }
}
