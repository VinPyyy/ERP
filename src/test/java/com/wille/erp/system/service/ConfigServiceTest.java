package com.wille.erp.system.service;

import com.wille.erp.common.exception.BizException;
import com.wille.erp.system.api.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 验收（issue 02）：类型转换与缓存刷新。
 * 不用 @Transactional：缓存是进程级的，事务回滚会造成缓存与库不一致的假象；
 * 改为 @AfterEach 恢复 V2 种子值（update 同步刷缓存，无跨测试污染）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfigServiceTest {

    @Autowired
    private ConfigService configService;

    @AfterEach
    void restoreSeedValues() {
        configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "false");
        configService.update(ConfigService.KEY_OVER_RECEIVE_RATIO, "0");
    }

    @Test
    void readsSeededDefaults() {
        assertThat(configService.getBool(ConfigService.KEY_ALLOW_NEGATIVE)).isFalse();
        assertThat(configService.getDecimal(ConfigService.KEY_OVER_RECEIVE_RATIO))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void updateRefreshesValueImmediately() {
        configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "true");
        assertThat(configService.getBool(ConfigService.KEY_ALLOW_NEGATIVE)).isTrue();

        configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "false");
        assertThat(configService.getBool(ConfigService.KEY_ALLOW_NEGATIVE)).isFalse();
    }

    @Test
    void decimalConversionKeepsPrecision() {
        configService.update(ConfigService.KEY_OVER_RECEIVE_RATIO, "0.15");
        assertThat(configService.getDecimal(ConfigService.KEY_OVER_RECEIVE_RATIO))
                .isEqualByComparingTo(new BigDecimal("0.15"));
    }

    @Test
    void unknownKeyThrowsBiz() {
        assertThatThrownBy(() -> configService.getInt("not.exist.key"))
                .isInstanceOf(BizException.class);
    }
}
