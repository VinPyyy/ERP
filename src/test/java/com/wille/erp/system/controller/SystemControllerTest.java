package com.wille.erp.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.system.dto.UserSaveRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统管理端点薄测：admin 登录 → 用户 CRUD 全链路、参数校验、权限拒绝（403）、
 * 权限树/参数/编号规则只读端点。V2 种子 admin 密码 admin123。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SystemControllerTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private com.wille.erp.system.api.ConfigService configService;
    @Autowired
    private com.wille.erp.system.service.PasswordCryptoService passwordCryptoService;

    private String loginToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(objectMapper.writeValueAsString(com.wille.erp.support.LoginTestRequests
                                .encrypted(passwordCryptoService, username, password))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private String adminToken() throws Exception {
        return loginToken("admin", "admin123");
    }

    @AfterEach
    void logout() {
        // config 缓存是进程级、不随事务回滚：手工把测试中改过的参数复原
        configService.update(com.wille.erp.system.api.ConfigService.KEY_OVER_RECEIVE_RATIO, "0");
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
    }

    @Test
    void userCrudFullChain() throws Exception {
        String token = adminToken();
        UserSaveRequest create = new UserSaveRequest();
        create.setUsername("crud01");
        create.setPassword("pwd123");
        create.setRealName("CRUD 测试");
        create.setRoleIds(List.of(3L));
        create.setWarehouseIds(List.of(0L));

        String created = mockMvc.perform(post("/api/system/users")
                        .header("Authorization", token)
                        .contentType(JSON).content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).path("data").asLong();

        mockMvc.perform(get("/api/system/users").header("Authorization", token)
                        .param("keyword", "crud01"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].roleNames[0]").value("仓管"));

        mockMvc.perform(get("/api/system/users/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.data.username").value("crud01"));

        create.setRealName("改名后");
        mockMvc.perform(put("/api/system/users/" + id).header("Authorization", token)
                        .contentType(JSON).content(objectMapper.writeValueAsString(create)))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(put("/api/system/users/" + id + "/password").header("Authorization", token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(com.wille.erp.support.LoginTestRequests
                                .encryptedReset(passwordCryptoService, "newpwd9"))))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(delete("/api/system/users/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));

        // 逻辑删后详情报"用户不存在"
        mockMvc.perform(get("/api/system/users/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID));
    }

    @Test
    void deleteAdminForbiddenViaHttp() throws Exception {
        String token = adminToken();

        mockMvc.perform(delete("/api/system/users/1").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("管理员")));
    }

    @Test
    void resetPasswordReplayAndShortPasswordRejected() throws Exception {
        String token = adminToken();
        UserSaveRequest create = new UserSaveRequest();
        create.setUsername("rpw01");
        create.setPassword("pwd123");
        create.setRoleIds(List.of(3L));
        create.setWarehouseIds(List.of(0L));
        String created = mockMvc.perform(post("/api/system/users").header("Authorization", token)
                        .contentType(JSON).content(objectMapper.writeValueAsString(create)))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).path("data").asLong();

        var request = com.wille.erp.support.LoginTestRequests.encryptedReset(passwordCryptoService, "newpwd9");
        String json = objectMapper.writeValueAsString(request);
        mockMvc.perform(put("/api/system/users/" + id + "/password").header("Authorization", token)
                        .contentType(JSON).content(json))
                .andExpect(jsonPath("$.code").value(0));

        // 同一 nonce 重放：挑战一次性，拒绝
        mockMvc.perform(put("/api/system/users/" + id + "/password").header("Authorization", token)
                        .contentType(JSON).content(json))
                .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_CHALLENGE_INVALID));

        // 解密后长度不足 6 位：PARAM_INVALID
        var shortPwd = objectMapper.writeValueAsString(
                com.wille.erp.support.LoginTestRequests.encryptedReset(passwordCryptoService, "123"));
        mockMvc.perform(put("/api/system/users/" + id + "/password").header("Authorization", token)
                        .contentType(JSON).content(shortPwd))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID));
    }

    @Test
    void salesRoleUserForbiddenOnUserManagement() throws Exception {
        // 非 admin（SALES 角色无 system:user:list 权限点）→ HTTP 403
        String createBody = objectMapper.writeValueAsString(java.util.Map.of(
                "username", "sales01", "password", "pwd123", "realName", "销售",
                "roleIds", List.of(2L), "warehouseIds", List.of(0L)));
        mockMvc.perform(post("/api/system/users").header("Authorization", adminToken())
                .contentType(JSON).content(createBody));

        String token = loginToken("sales01", "pwd123");

        mockMvc.perform(get("/api/system/users").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void permissionTreeConfigsAndDocNoRulesReadable() throws Exception {
        String token = adminToken();

        mockMvc.perform(get("/api/system/permissions/tree").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(5));

        mockMvc.perform(get("/api/system/configs").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(put("/api/system/configs/purchase.over.receive.ratio")
                        .header("Authorization", token)
                        .contentType(JSON).content("{\"value\":\"0.05\"}"))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/system/doc-no-rules").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(11));
    }
}
