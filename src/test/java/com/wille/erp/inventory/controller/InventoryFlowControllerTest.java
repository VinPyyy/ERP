package com.wille.erp.inventory.controller;

import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.support.DataFactory;
import com.wille.erp.support.LoginTestRequests;
import com.wille.erp.system.service.PasswordCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * replay-check 端点薄测（M3a 任务 5）：空库返回 consistent=true；无权限 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InventoryFlowControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private PasswordCryptoService passwordCryptoService;

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(
                        org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                                LoginTestRequests.encrypted(passwordCryptoService, username, password))))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("data").path("token").asText();
    }

    @Test
    void replayCheckReturnsConsistentOnCleanDb() throws Exception {
        mockMvc.perform(get("/api/inventory/flows/replay-check")
                        .header("Authorization", login("admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.consistent").value(true))
                .andExpect(jsonPath("$.data.diffs").isArray());
    }

    @Test
    void replayCheckForbiddenWithoutPerm() throws Exception {
        dataFactory.roleWithPerms("R_NOINV", "无库存角色", "basedata:product:list");
        dataFactory.user("noinv01", "pwd123", "R_NOINV");

        mockMvc.perform(get("/api/inventory/flows/replay-check")
                        .header("Authorization", login("noinv01", "pwd123")))
                .andExpect(status().isForbidden());
    }
}
