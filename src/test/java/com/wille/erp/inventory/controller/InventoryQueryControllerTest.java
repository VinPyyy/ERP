package com.wille.erp.inventory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wille.erp.support.DataFactory;
import com.wille.erp.support.LoginTestRequests;
import com.wille.erp.system.service.PasswordCryptoService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 查询端点权限薄测（M3c 任务 1）：无权限点 403。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InventoryQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private PasswordCryptoService passwordCryptoService;

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(LoginTestRequests.encrypted(
                                passwordCryptoService, username, password))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    @Test
    void stocksForbiddenWithoutPerm() throws Exception {
        dataFactory.roleWithPerms("R_QNONE", "无库存权限", "basedata:product:list");
        dataFactory.user("qnone01", "pwd123", "R_QNONE");

        mockMvc.perform(get("/api/inventory/stocks").header("Authorization", login("qnone01", "pwd123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void snAdjustForbiddenWithoutPerm() throws Exception {
        dataFactory.roleWithPerms("R_QSNNO", "无SN调整", "inventory:stock:list");
        dataFactory.user("qsnno01", "pwd123", "R_QSNNO");

        mockMvc.perform(post("/api/inventory/sns/1/adjust").header("Authorization", login("qsnno01", "pwd123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OUT\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
