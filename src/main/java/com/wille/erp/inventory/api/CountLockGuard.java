package com.wille.erp.inventory.api;

import java.util.Collection;

/**
 * 盘点锁守卫（ADR-0004，spec §3d）：COUNTING(15) 状态的盘点单即范围锁。
 * 单据审核入口（入库/退货/订单/调拨/期初）调用；命中即拒绝审核。
 */
public interface CountLockGuard {

    boolean counting(Long warehouseId, Collection<Long> skuIds);
}
