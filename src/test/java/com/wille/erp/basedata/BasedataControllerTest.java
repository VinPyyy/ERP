package com.wille.erp.basedata;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wille.erp.basedata.dto.PartnerSaveRequest;
import com.wille.erp.basedata.dto.ProductSaveRequest;
import com.wille.erp.basedata.excel.ProductImportModel;
import com.wille.erp.common.enums.MgmtMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.stp.StpUtil;

/**
 * M2 端点薄测：商品 CRUD/启停用/导入（multipart）/导出（xlsx 字节）；
 * 类别/仓库/客户同构端点冒烟。V2 admin/admin123。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BasedataControllerTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private com.wille.erp.system.service.PasswordCryptoService passwordCryptoService;
    @Autowired
    private com.wille.erp.support.DataFactory dataFactory;

    private String loginToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(objectMapper.writeValueAsString(com.wille.erp.support.LoginTestRequests
                                .encrypted(passwordCryptoService, username, password))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(objectMapper.writeValueAsString(com.wille.erp.support.LoginTestRequests
                                .encrypted(passwordCryptoService, "admin", "admin123"))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    @AfterEach
    void logout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
    }

    @Test
    void productCrudAndStatusToggle() throws Exception {
        String token = adminToken();
        ProductSaveRequest create = new ProductSaveRequest();
        create.setSkuCode("BC001");
        create.setName("饼干");
        create.setUnit("盒");
        create.setMgmtMode(MgmtMode.NONE);

        String created = mockMvc.perform(post("/api/basedata/products")
                        .header("Authorization", token).contentType(JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).path("data").asLong();

        mockMvc.perform(get("/api/basedata/products").header("Authorization", token).param("keyword", "BC001"))
                .andExpect(jsonPath("$.data.total").value(1));

        create.setExpireWarnDays(30);
        mockMvc.perform(put("/api/basedata/products/" + id).header("Authorization", token)
                        .contentType(JSON).content(objectMapper.writeValueAsString(create)))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/basedata/products/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.data.expireWarnDays").value(30));

        mockMvc.perform(delete("/api/basedata/products/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void productImportValidAndInvalid() throws Exception {
        String token = adminToken();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProductImportModel ok = new ProductImportModel();
        ok.setSkuCode("IMP001");
        ok.setName("导入品");
        ok.setUnit("个");
        ok.setMgmtMode("不管");
        EasyExcel.write(out, ProductImportModel.class).sheet("商品").doWrite(List.of(ok));

        mockMvc.perform(multipart("/api/basedata/products/import")
                        .file(new MockMultipartFile("file", "products.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                out.toByteArray()))
                        .header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.successCount").value(1));

        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        ProductImportModel broken = new ProductImportModel();
        broken.setSkuCode("IMP001");
        broken.setName("");
        broken.setUnit("个");
        broken.setMgmtMode("抽签");
        EasyExcel.write(bad, ProductImportModel.class).sheet("商品").doWrite(List.of(broken));

        mockMvc.perform(multipart("/api/basedata/products/import")
                        .file(new MockMultipartFile("file", "bad.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                bad.toByteArray()))
                        .header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.errors[0].row").value(1));
    }

    @Test
    void productExportReturnsXlsx() throws Exception {
        String token = adminToken();

        byte[] bytes = mockMvc.perform(get("/api/basedata/products/export").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(bytes).isNotEmpty();
    }

    @Test
    void categoryWarehouseCustomerEndpointsSmoke() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/api/basedata/categories").header("Authorization", token)
                        .contentType(JSON).content("{\"name\":\"烟测试类\",\"sortNo\":9}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/basedata/categories").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/basedata/warehouses").header("Authorization", token)
                        .contentType(JSON).content("{\"code\":\"WH-S\",\"name\":\"烟测仓\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/basedata/warehouses").header("Authorization", token))
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        PartnerSaveRequest customer = new PartnerSaveRequest();
        customer.setCode("CUST-S");
        customer.setName("烟测客户");
        mockMvc.perform(post("/api/basedata/customers").header("Authorization", token)
                        .contentType(JSON).content(objectMapper.writeValueAsString(customer)))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/basedata/customers").header("Authorization", token))
                .andExpect(jsonPath("$.data.total").value(1));

        PartnerSaveRequest supplier = new PartnerSaveRequest();
        supplier.setCode("SUP-S");
        supplier.setName("烟测供应商");
        mockMvc.perform(post("/api/basedata/suppliers").header("Authorization", token)
                        .contentType(JSON).content(objectMapper.writeValueAsString(supplier)))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void referenceOptionsEndpointsAreLoginOnly() throws Exception {
        // 下拉引用数据（仓库/供应商/商品）登录即可，不要求本模块 list 权限
        dataFactory.roleWithPerms("R_BOPT", "仅用户模块", "system:user:list");
        dataFactory.user("bopt01", "pwd123", "R_BOPT");
        String token = loginToken("bopt01", "pwd123");

        mockMvc.perform(get("/api/basedata/warehouses").header("Authorization", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/basedata/warehouses/options").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/basedata/suppliers/options").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/basedata/products/options").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/basedata/customers/options").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
