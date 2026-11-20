package com.wille.erp.support;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 工具测试（默认禁用）：重新生成 admin 初始密码的 BCrypt hash。
 * 用法：临时去掉 @Disabled，mvn test -Dtest=GenAdminHash，取 ADMIN_HASH= 输出替换 V2 后重建库。
 */
@Disabled("手工工具，不参与常规构建")
class GenAdminHash {

    @Test
    void generate() {
        System.out.println("ADMIN_HASH=" + new BCryptPasswordEncoder().encode("admin123"));
    }
}
