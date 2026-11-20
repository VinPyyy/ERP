package com.wille.erp.support;

/**
 * 对账器断言工具（M3 实现，spec §3c/§6 测试基建）：
 * 重放 inv_flow 比对四层不变量——
 * 1. 管批次 SKU：SUM(inv_lot_stock.qty) = inv_stock.qty
 * 2. 管 SN SKU：COUNT(inv_sn IN_STOCK @仓) = inv_stock.qty
 * 3. locked_qty = SUM(inv_stock_lock.remain_qty)
 * 4. 每行流水余额快照 = 上一行快照 + 本行变动；cost_amount ≈ qty × cost_price
 * 每个库存相关测试收尾必跑。
 */
public final class StockInvariantAsserter {

    private StockInvariantAsserter() {
    }

    public static void assertInvariants() {
        throw new UnsupportedOperationException("M3 实现：流水重放对账断言");
    }
}
