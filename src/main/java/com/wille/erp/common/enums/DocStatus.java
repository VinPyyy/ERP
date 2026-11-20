package com.wille.erp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一单据状态机（GLOSSARY"单据状态机"）：
 * 草稿(10) → [盘点单另有盘点中(15)] → 已审核(20) → 已完成(30)/已关闭(40)；任何非终态可作废(50)。
 * 枚举值留间隔以便扩展。
 */
@Getter
@AllArgsConstructor
public enum DocStatus {

    DRAFT(10, "草稿"),
    COUNTING(15, "盘点中"),
    AUDITED(20, "已审核"),
    COMPLETED(30, "已完成"),
    CLOSED(40, "已关闭"),
    VOIDED(50, "已作废");

    @EnumValue
    private final int code;
    private final String desc;
}
