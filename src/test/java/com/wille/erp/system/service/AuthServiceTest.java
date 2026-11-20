package com.wille.erp.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.support.DataFactory;
import com.wille.erp.system.dto.LoginRequest;
import com.wille.erp.system.dto.LoginResponse;
import com.wille.erp.system.dto.MeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 验收（issue 02）：登录成功/密码错/停用/不存在，me 聚合角色权限码并集与可见仓库。
 * Sa-Token 需要 servlet 上下文：MockHttpServletRequest 绑定到 RequestContextHolder。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthServiceTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private DataFactory dataFactory;

    @BeforeEach
    void bindMockRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), new MockHttpServletResponse()));
    }

    @AfterEach
    void clearSession() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    private static LoginRequest req(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    @Test
    void seededAdminCanLoginWithInitialPassword() {
        // 验证 V2 种子的 BCrypt hash 有效（admin/admin123，上线后应立即改密）
        LoginResponse response = authService.login(req("admin", "admin123"));

        assertThat(response.getToken()).isNotBlank();
    }

    @Test
    void loginSuccessReturnsUsableToken() {
        dataFactory.user("zhang3", "pwd123", "SALES");

        LoginResponse response = authService.login(req("zhang3", "pwd123"));

        assertThat(response.getToken()).isNotBlank();
        assertThat(StpUtil.getLoginIdByToken(response.getToken())).isNotNull();
    }

    @Test
    void loginWrongPasswordThrowsBiz() {
        dataFactory.user("zhang3", "pwd123", "SALES");

        assertThatThrownBy(() -> authService.login(req("zhang3", "wrong-pwd")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名或密码");
    }

    @Test
    void loginUnknownUserThrowsSameMessage() {
        // 不泄露用户是否存在：统一"用户名或密码错误"
        assertThatThrownBy(() -> authService.login(req("ghost", "whatever")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名或密码");
    }

    @Test
    void loginDisabledUserThrowsBiz() {
        dataFactory.disabledUser("wang5", "pwd123", "SALES");

        assertThatThrownBy(() -> authService.login(req("wang5", "pwd123")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void meAggregatesRolePermUnionAndWarehouses() {
        dataFactory.roleWithPerms("R_SALES_A", "销售A", "sale:order:list", "sale:order:audit");
        dataFactory.roleWithPerms("R_SALES_B", "销售B", "sale:order:audit", "sale:outbound:list");
        var user = dataFactory.user("li4", "pwd123", "R_SALES_A", "R_SALES_B");
        dataFactory.bindWarehouses(user.getId(), 7L, 8L);
        authService.login(req("li4", "pwd123"));

        MeResponse me = authService.me();

        assertThat(me.getId()).isEqualTo(user.getId());
        assertThat(me.getUsername()).isEqualTo("li4");
        // 权限码 = 两角色并集且去重
        assertThat(me.getPerms()).containsExactlyInAnyOrder(
                "sale:order:list", "sale:order:audit", "sale:outbound:list");
        assertThat(me.getRoles()).containsExactlyInAnyOrder("R_SALES_A", "R_SALES_B");
        assertThat(me.getWarehouses()).containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    void meReportsAllWarehousesWhenBoundZero() {
        var user = dataFactory.user("boss", "pwd123", "ADMIN");
        dataFactory.bindWarehouses(user.getId(), 0L);
        authService.login(req("boss", "pwd123"));

        MeResponse me = authService.me();

        assertThat(me.getWarehouses()).containsExactly(0L);
    }

    @Test
    void logoutInvalidatesToken() {
        dataFactory.user("zhang3", "pwd123", "SALES");
        LoginResponse response = authService.login(req("zhang3", "pwd123"));

        authService.logout();

        assertThat(StpUtil.getLoginIdByToken(response.getToken())).isNull();
    }
}
