package com.wille.erp.inventory.core;

import java.math.BigDecimal;

/**
 * 库存主行的计价状态（仓库×SKU 粒度，ADR-0001/0002）。
 * 金额权威（costAmount）、单价派生（costPrice = costAmount / qty，6 位 HALF_UP）。
 */
public record StockState(BigDecimal qty, BigDecimal costAmount, BigDecimal costPrice) {

    public static StockState zero() {
        return new StockState(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
