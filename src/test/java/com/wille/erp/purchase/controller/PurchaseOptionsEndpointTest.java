package com.wille.erp.purchase.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.wille.erp.support.DataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 源单下拉端点权限契约（方案 A）：orders/options 放行能建入库/退货的角色（OR），
 * inbounds/options 放行能建退货的角色；不放开模块 list 权限。
 *
 * 正向用 admin（V2 种子权限已提交）——测试事务内 grant 的权限点对请求拦截器所在连接
 * 不可见（套件级 harness 限制，生产无此问题），故"被授权用户正向通过"不经 MockMvc 断言，
 * 与既有套件约定一致；auditedOptions 内容逻辑在 OrderInboundServiceTest 服务层直测。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PurchaseOptionsEndpointTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataFactory dataFactory;

    @BeforeEach
    void bindRequestContext() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), new MockHttpServletResponse()));
    }

    @AfterEach
    void clearLogin() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    private String tokenAs(long userId) {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        StpUtil.login(userId);
        return StpUtil.getTokenValue();
    }

    @Test
    void adminCanReadBothDocOptions() throws Exception {
        String token = tokenAs(1L);

        mockMvc.perform(get("/api/purchase/orders/options").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/purchase/inbounds/options").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void userWithoutDownstreamCreatePermBlocked() throws Exception {
        dataFactory.roleWithPerms("R_PLAIN", "仅用户模块", "system:user:list");
        var u = dataFactory.user("plain01", "pwd123", "R_PLAIN");
        String token = tokenAs(u.getId());

        mockMvc.perform(get("/api/purchase/orders/options").header("Authorization", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/purchase/inbounds/options").header("Authorization", token))
                .andExpect(status().isForbidden());
    }
}
