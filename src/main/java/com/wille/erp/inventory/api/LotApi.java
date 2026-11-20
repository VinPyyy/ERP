package com.wille.erp.inventory.api;

import java.time.LocalDate;

/**
 * 批次主档物化门面（purchase 入库/期初共用）：UNIQUE(sku_id, lot_no) 复用，
 * expiry = 生产日期 + 商品保质期（保质期空则 expiry 空）。
 */
public interface LotApi {

    Long materialize(Long skuId, String lotNo, LocalDate productionDate);
}
