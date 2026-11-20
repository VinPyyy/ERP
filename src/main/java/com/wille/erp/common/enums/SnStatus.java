package com.wille.erp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SN 两态生命周期（spec §3b）：在库 ⇄ 已出库。
 */
@Getter
@AllArgsConstructor
public enum SnStatus {

    IN_STOCK(1, "在库"),
    OUT(2, "已出库");

    @EnumValue
    private final int code;
    private final String desc;
}
