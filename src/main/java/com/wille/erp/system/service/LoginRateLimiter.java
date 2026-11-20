package com.wille.erp.system.service;

import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：同一用户名 15 分钟窗口内累计 5 次密码错误锁定 15 分钟；
 * 登录成功即清零。进程内存态（与 Sa-Token 会话同生命周期），多实例部署时替换为共享存储。
 */
@Component
public class LoginRateLimiter {

    static final int MAX_FAILURES = 5;
    static final long WINDOW_MS = 15 * 60 * 1000L;

    private static final class State {
        int count;
        long windowStart;
        volatile long lockUntil;
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public void assertNotLocked(String key) {
        State s = states.get(key);
        long now = System.currentTimeMillis();
        if (s != null && s.lockUntil > now) {
            long minutes = Math.max(1, (s.lockUntil - now + 59_999) / 60_000);
            throw new BizException(ErrorCode.LOGIN_LOCKED, "失败次数过多，请 " + minutes + " 分钟后重试");
        }
    }

    public void onFailure(String key) {
        long now = System.currentTimeMillis();
        states.compute(key, (k, s) -> {
            if (s == null || now - s.windowStart > WINDOW_MS) {
                s = new State();
                s.windowStart = now;
            }
            s.count++;
            if (s.count >= MAX_FAILURES) {
                s.lockUntil = now + WINDOW_MS;
                s.count = 0;
                s.windowStart = now;
            }
            return s;
        });
    }

    public void onSuccess(String key) {
        states.remove(key);
    }
}
