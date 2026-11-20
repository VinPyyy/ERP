package com.wille.erp.inventory.core;

/**
 * 重放差异项：scope=差异类别（balance_qty/final_vs_stock/lot_sum/sn_count/locked_sum），
 * refId=定位到的行 id（流水 id 或库存行 id）。
 */
public record ReplayDiff(String scope, Long refId, String detail) {
}
