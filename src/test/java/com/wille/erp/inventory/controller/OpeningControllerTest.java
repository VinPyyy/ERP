package com.wille.erp.inventory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wille.erp.common.enums.MgmtMode;
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

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 期初端点薄测（M3b 任务 1）：admin 全链路 200；无权限点 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OpeningControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private PasswordCryptoService passwordCryptoService;

    private String token(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                LoginTestRequests.encrypted(passwordCryptoService, username, password))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    @Test
    void adminCreatesAndAuditsOpening() throws Exception {
        var wh = dataFactory.warehouse("OPC-W1");
        var p = dataFactory.product("OPC-P1", MgmtMode.NONE);
        String token = token("admin", "admin123");

        String created = mockMvc.perform(post("/api/inventory/openings").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "warehouseId", wh.getId(),
                                "items", List.of(Map.of("skuId", p.getId(), "qty", "10", "costPrice", "5"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).path("data").asLong();

        mockMvc.perform(post("/api/inventory/openings/" + id + "/audit").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/inventory/openings/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.data.status").value(20))
                .andExpect(jsonPath("$.data.docNo").exists());
    }

    @Test
    void auditForbiddenWithoutPerm() throws Exception {
        dataFactory.roleWithPerms("R_OPNO", "无期初权限", "basedata:product:list");
        dataFactory.user("opno01", "pwd123", "R_OPNO");

        mockMvc.perform(post("/api/inventory/openings/1/audit")
                        .header("Authorization", token("opno01", "pwd123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void voidForbiddenWithoutPerm() throws Exception {
        dataFactory.roleWithPerms("R_OPNO2", "无期初权限2", "basedata:product:list");
        dataFactory.user("opno02", "pwd123", "R_OPNO2");

        mockMvc.perform(post("/api/inventory/openings/1/void")
                        .header("Authorization", token("opno02", "pwd123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
