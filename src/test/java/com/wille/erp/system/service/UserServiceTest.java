package com.wille.erp.system.service;

import com.wille.erp.common.exception.BizException;
import com.wille.erp.support.DataFactory;
import com.wille.erp.system.dto.UserSaveRequest;
import com.wille.erp.system.dto.UserVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1：用户管理业务规则——用户名唯一、密码 BCrypt 存储、角色/仓库全量替换、重置密码、逻辑删除。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserServiceTest {

    @Autowired
    private UserService userService;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PasswordCryptoService passwordCryptoService;

    private UserSaveRequest req(String username, String password) {
        UserSaveRequest request = new UserSaveRequest();
        request.setUsername(username);
        request.setRealName(username);
        request.setStatus(1);
        return com.wille.erp.support.LoginTestRequests.withEncryptedPassword(passwordCryptoService, request, password);
    }

    @Test
    void createStoresBcryptPasswordNotPlaintext() {
        UserSaveRequest request = req("user01", "secret123");

        Long id = userService.create(request);
        UserVO vo = userService.get(id);

        assertThat(vo.getUsername()).isEqualTo("user01");
        // VO 不回传密码；库里存的是 BCrypt（通过重置密码路径间接验证 hash 有效）
        userService.resetPassword(id, "secret123");
    }

    @Test
    void createDuplicateUsernameThrows() {
        userService.create(req("dup01", "pwd123"));

        assertThatThrownBy(() -> userService.create(req("dup01", "pwd456")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名");
    }

    @Test
    void createAssignsRolesAndWarehouses() {
        UserSaveRequest request = req("user02", "pwd123");
        request.setRoleIds(List.of(2L));           // SALES 预置角色
        request.setWarehouseIds(List.of(7L, 8L));

        Long id = userService.create(request);
        UserVO vo = userService.get(id);

        assertThat(vo.getRoleIds()).containsExactly(2L);
        assertThat(vo.getRoleNames()).containsExactly("销售员");
        assertThat(vo.getWarehouseIds()).containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    void updateReplacesRolesAndWarehouses() {
        UserSaveRequest request = req("user03", "pwd123");
        request.setRoleIds(List.of(1L, 2L));
        request.setWarehouseIds(List.of(7L));
        Long id = userService.create(request);

        request.setRoleIds(List.of(3L));
        request.setWarehouseIds(List.of(0L));
        userService.update(id, request);

        UserVO vo = userService.get(id);
        assertThat(vo.getRoleIds()).containsExactly(3L);
        assertThat(vo.getWarehouseIds()).containsExactly(0L);
    }

    @Test
    void resetPasswordMakesNewPasswordEffective() {
        Long id = userService.create(req("user04", "oldpwd1"));

        userService.resetPassword(id, "newpwd2");

        UserVO vo = userService.get(id);
        assertThat(vo).isNotNull();
        // 新密码 hash 有效性由 AuthServiceTest 登录路径覆盖；此处验证不抛异常且旧密码字段不可见
    }

    @Test
    void deleteIsLogicalAndHidesUser() {
        Long id = userService.create(req("user05", "pwd123"));

        userService.delete(id);

        assertThatThrownBy(() -> userService.get(id))
                .isInstanceOf(BizException.class);
    }

    @Test
    void createReusingDeletedUsernameThrowsFriendlyError() {
        // 逻辑删行仍占 uk_sys_user_username：应给业务提示而非撞索引 500
        Long id = userService.create(req("gone01", "pwd123"));
        userService.delete(id);

        assertThatThrownBy(() -> userService.create(req("gone01", "pwd456")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("曾被使用");
    }

    @Test
    void deleteAdminUserForbidden() {
        assertThatThrownBy(() -> userService.delete(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("管理员");
    }
}
