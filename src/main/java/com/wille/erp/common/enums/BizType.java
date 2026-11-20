package com.wille.erp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 库存流水业务类型（inv_flow.biz_type，spec §3f）。
 * 方向语义：*_IN 入库方向（qty 为正），*_OUT 出库方向（qty 为负）。
 */
@Getter
@AllArgsConstructor
public enum BizType {

    OPENING("期初入库", true),
    PUR_IN("采购入库", true),
    PUR_RETURN_OUT("采购退货出库", false),
    SALE_OUT("销售出库", false),
    SAL_RETURN_IN("销售退货入库", true),
    TRANSFER_OUT("调拨出库", false),
    TRANSFER_IN("调拨入库", true),
    COUNT_GAIN_IN("盘盈入库", true),
    COUNT_LOSS_OUT("盘亏出库", false);

    private final String desc;
    /** true = 入库方向 */
    private final boolean inbound;
}
