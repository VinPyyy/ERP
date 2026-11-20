package com.wille.erp.system.api;

import java.math.BigDecimal;

/**
 * 系统参数读取（system 模块对外 API）：进程内缓存，更新即刷新（无 Redis，spec §2.1）。
 */
public interface ConfigService {

    /** 参数键（与 V2 种子一致） */
    String KEY_ALLOW_NEGATIVE = "stock.allow.negative";
    String KEY_OVER_RECEIVE_RATIO = "purchase.over.receive.ratio";

    boolean getBool(String key);

    BigDecimal getDecimal(String key);

    int getInt(String key);

    String getString(String key);

    /** 修改参数值并刷新缓存 */
    void update(String key, String value);
}
