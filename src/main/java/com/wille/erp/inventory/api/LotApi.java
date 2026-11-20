package com.wille.erp.inventory.api;

import java.time.LocalDate;

/**
 * 批次主档门面（purchase 入库/退货、期初共用）：UNIQUE(sku_id, lot_no) 复用，
 * expiry = 生产日期 + 商品保质期（保质期空则 expiry 空）。
 * materialize 仅限入库方向；出库方向（退货）必须 find 既有批次，禁止凭空造批次（M4 审查 I5）。
 */
public interface LotApi {

    Long materialize(Long skuId, String lotNo, LocalDate productionDate);

    /** 按 sku+批号 查既有批次；不存在返回 null */
    LotView find(Long skuId, String lotNo);

    /** 按 id 查批次（有源退货用源行 lotId 反查批号）；不存在返回 null */
    LotView byId(Long lotId);

    record LotView(Long id, String lotNo) {
    }
}
