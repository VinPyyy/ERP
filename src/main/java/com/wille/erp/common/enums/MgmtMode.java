package com.wille.erp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 商品管理维度（GLOSSARY"管理维度"）：不管 / 管批次 / 管 SN / 批次+SN。
 * 商品被库存/流水/单据引用后禁改（spec §8-15）。
 */
@Getter
@AllArgsConstructor
public enum MgmtMode {

    NONE(1, "不管批次不管SN"),
    LOT(2, "管批次"),
    SN(3, "管SN"),
    LOT_SN(4, "批次+SN");

    @EnumValue
    private final int code;
    private final String desc;

    public boolean lotManaged() {
        return this == LOT || this == LOT_SN;
    }

    public boolean snManaged() {
        return this == SN || this == LOT_SN;
    }
}
