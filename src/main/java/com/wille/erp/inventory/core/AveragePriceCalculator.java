package com.wille.erp.inventory.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 移动加权平均纯函数（spec §3a）：金额权威、单价派生、出清归零。
 * 不做业务校验——超卖/负库存由 StockService 在调用前拦截，这里只算数，
 * 保证对账器可以用同一函数重放历史。
 */
public final class AveragePriceCalculator {

    private static final int PRICE_SCALE = 6;
    private static final int AMOUNT_SCALE = 2;

    private AveragePriceCalculator() {
    }

    public record InResult(StockState next, BigDecimal costAmountDelta) {
    }

    /** carryAmount ≥ 0（出清时 = 原 costAmount 全额，吃尾差不留残值） */
    public record OutResult(StockState next, BigDecimal carryAmount) {
    }

    public static InResult applyIn(StockState s, BigDecimal qtyIn, BigDecimal priceIn) {
        BigDecimal delta = round2(qtyIn.multiply(priceIn));
        BigDecimal qty = s.qty().add(qtyIn);
        BigDecimal amount = round2(s.costAmount().add(delta));
        return new InResult(new StockState(qty, amount, derivePrice(amount, qty)), delta);
    }

    public static OutResult applyOut(StockState s, BigDecimal qtyOut) {
        BigDecimal carry = qtyOut.compareTo(s.qty()) == 0
                ? s.costAmount()
                : round2(qtyOut.multiply(s.costPrice()));
        BigDecimal qty = s.qty().subtract(qtyOut);
        BigDecimal amount = round2(s.costAmount().subtract(carry));
        return new OutResult(new StockState(qty, amount, derivePrice(amount, qty)), carry);
    }

    /** 派生均价：金额权威、6 位 HALF_UP；qty 归零则价归零（冲正/重放共用同一口径） */
    public static BigDecimal derivePrice(BigDecimal amount, BigDecimal qty) {
        return qty.signum() == 0
                ? BigDecimal.ZERO
                : amount.divide(qty, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal round2(BigDecimal v) {
        return v.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }
}
