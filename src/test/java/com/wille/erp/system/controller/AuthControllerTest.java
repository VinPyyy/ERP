package com.wille.erp.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.support.DataFactory;
import com.wille.erp.system.dto.LoginRequest;
import com.wille.erp.system.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证端点薄测（spec §5.3）：login 成功/失败/参数校验、me 鉴权链路（token → 权限码）。
 * 全上下文 MockMvc；Sa-Token 会话为进程内存态，测后登出清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataFactory dataFactory;

    private String loginAndGetToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq(username, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private static LoginRequest loginReq(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    @Test
    void loginReturnsTokenAndMeCarriesPermsAndWarehouses() throws Exception {
        // V2 里仅 ADMIN 角色有授权，测试角色显式授权；SALES 无权限点可反证隔离
        dataFactory.roleWithPerms("R_AUTH", "认证测试角色", "sale:order:list", "sale:order:audit");
        SysUser user = dataFactory.user("auth01", "pwd123", "R_AUTH", "SALES");
        dataFactory.bindWarehouses(user.getId(), 7L);
        String token = loginAndGetToken("auth01", "pwd123");

        try {
            mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.username").value("auth01"))
                    .andExpect(jsonPath("$.data.perms", org.hamcrest.Matchers.hasItem("sale:order:list")))
                    .andExpect(jsonPath("$.data.perms",
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("system:user:list"))))
                    .andExpect(jsonPath("$.data.warehouses", org.hamcrest.Matchers.contains(7)));
        } finally {
            StpUtil.logout();
        }
    }

    @Test
    void loginWrongPasswordReturnsHttp200WithBizCode() throws Exception {
        dataFactory.user("auth02", "pwd123", "SALES");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq("auth02", "bad"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_FAILED));
    }

    @Test
    void loginBlankUsernameReturnsParamInvalid() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID));
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
