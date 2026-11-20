package com.wille.erp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单行执行状态（分批履约，spec §2.4/2.5）：执行中 → 已完成（自动）/已关闭（手动释放剩余）。
 */
@Getter
@AllArgsConstructor
public enum LineStatus {

    EXECUTING(10, "执行中"),
    COMPLETED(30, "已完成"),
    CLOSED(40, "已关闭");

    @EnumValue
    private final int code;
    private final String desc;

    public boolean terminal() {
        return this == COMPLETED || this == CLOSED;
    }
}
