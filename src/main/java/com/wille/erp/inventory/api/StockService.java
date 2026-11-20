package com.wille.erp.inventory.api;

import java.math.BigDecimal;

/**
 * 全系统唯一动库存门面（spec §1/§3，ArchUnit 守护：业务模块不得绕过直接动 inv_* 表）。
 * 实现约束：单事务、按 (warehouse_id, sku_id) 升序 FOR UPDATE（ADR-0005）、
 * 每笔写 inv_flow 含余额快照（§3f）、四层不变量收尾成立。
 */
public interface StockService {

    /** 入库：入10@10 → 金额+round2(qty×price)、均价派生（§3a） */
    void inbound(StockCmd.MoveCmd cmd);

    /** 出库：按均价结转、出清归零；负库存/批次/SN 校验在此层拦截 */
    void outbound(StockCmd.MoveCmd cmd);

    /** 订单审核锁定：可用量校验（受 stock.allow.negative 开关）→ 明细+汇总双层 */
    void lock(StockCmd.LockCmd cmd);

    /** 按订单行全额释放锁定 */
    void unlock(StockCmd.LockRef ref);

    /** 作废冲正：取原流水快照原路反向，reverse_flow_id 配对防重复（ADR-0003） */
    void reverse(StockCmd.ReverseCmd cmd);

    /** 可用量 = qty − locked_qty */
    BigDecimal available(Long warehouseId, Long skuId);
}
