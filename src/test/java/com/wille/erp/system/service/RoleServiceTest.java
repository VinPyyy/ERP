package com.wille.erp.system.service;

import com.wille.erp.common.exception.BizException;
import com.wille.erp.system.dto.PermissionNode;
import com.wille.erp.system.dto.RoleSaveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1：角色管理——编码唯一、预置角色不可删、授权全量替换；权限树结构。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoleServiceTest {

    @Autowired
    private RoleService roleService;
    @Autowired
    private PermissionService permissionService;

    private RoleSaveRequest req(String code, String name) {
        RoleSaveRequest request = new RoleSaveRequest();
        request.setRoleCode(code);
        request.setRoleName(name);
        return request;
    }

    @Test
    void createDuplicateRoleCodeThrows() {
        roleService.create(req("R_DUP", "重复角色"));

        assertThatThrownBy(() -> roleService.create(req("R_DUP", "再来一个")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色编码");
    }

    @Test
    void deleteBuiltInRoleThrows() {
        // V2 预置五角色 built_in=1（id 1..5）
        assertThatThrownBy(() -> roleService.delete(2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("预置角色");
    }

    @Test
    void deleteCustomRoleSucceeds() {
        Long id = roleService.create(req("R_TMP", "临时角色"));

        roleService.delete(id);

        assertThat(roleService.list()).noneMatch(r -> r.getId().equals(id));
    }

    @Test
    void grantPermissionsReplacesPrevious() {
        Long roleId = roleService.create(req("R_GRANT", "授权角色"));
        List<PermissionNode> tree = permissionService.tree();
        Long firstPermId = tree.get(0).getId();
        Long secondPermId = tree.get(1).getId();

        roleService.grantPermissions(roleId, List.of(firstPermId));
        assertThat(roleService.permissionIds(roleId)).containsExactly(firstPermId);

        roleService.grantPermissions(roleId, List.of(secondPermId));
        assertThat(roleService.permissionIds(roleId)).containsExactly(secondPermId);
    }

    @Test
    void permissionTreeHasFiveRootsWithChildren() {
        List<PermissionNode> tree = permissionService.tree();

        // V2 种子：5 个根菜单，各带页面子节点
        assertThat(tree).hasSize(5);
        assertThat(tree).allSatisfy(root -> assertThat(root.getChildren()).isNotEmpty());
    }
}
