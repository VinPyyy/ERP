package com.wille.erp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统参数值类型（sys_config.value_type），ConfigService 按类型转换。
 */
@Getter
@AllArgsConstructor
public enum ConfigValueType {

    BOOL("布尔"),
    DECIMAL("小数"),
    INT("整数"),
    STRING("字符串");

    private final String desc;
}
