package com.wille.erp.system.service;

import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录失败限流：连续 5 次失败锁定，登录成功清零，不同账号互不影响。
 */
class LoginRateLimiterTest {

    private final LoginRateLimiter limiter = new LoginRateLimiter();

    @Test
    void allowsUpToMaxFailures() {
        for (int i = 0; i < 4; i++) {
            limiter.onFailure("zhang3");
        }
        assertThatCode(() -> limiter.assertNotLocked("zhang3")).doesNotThrowAnyException();
    }

    @Test
    void blocksAfterMaxFailures() {
        for (int i = 0; i < 5; i++) {
            limiter.onFailure("zhang3");
        }
        assertThatThrownBy(() -> limiter.assertNotLocked("zhang3"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.LOGIN_LOCKED);
    }

    @Test
    void successResetsCounter() {
        for (int i = 0; i < 4; i++) {
            limiter.onFailure("zhang3");
        }
        limiter.onSuccess("zhang3");

        for (int i = 0; i < 4; i++) {
            limiter.onFailure("zhang3");
        }
        assertThatCode(() -> limiter.assertNotLocked("zhang3")).doesNotThrowAnyException();
    }

    @Test
    void isolationBetweenAccounts() {
        for (int i = 0; i < 5; i++) {
            limiter.onFailure("zhang3");
        }
        assertThatCode(() -> limiter.assertNotLocked("li4")).doesNotThrowAnyException();
    }
}
