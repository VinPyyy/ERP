package com.wille.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 完整上下文冒烟测试：需要本机 Docker MySQL 8（3307/erp_test）就绪，
 * Flyway 会自动执行 V1/V2 迁移——迁移成功本身即 DDL 的验证。
 */
@SpringBootTest
@ActiveProfiles("test")
class ErpApplicationTests {

    @Test
    void contextLoads() {
    }
}
