package com.wille.erp.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc OpenAPI 文档（/swagger-ui.html）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI erpOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ERP 进销存 API")
                .version("v1")
                .description("一期：基础资料 + 采购 + 销售 + 库存"));
    }
}
