package com.wille.erp.inventory.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 移动加权均价纯函数（spec §3a）。断言值即验收锚点：
 * 入10@10 → 入5@13 → 出8 = 均价11、结转88、余7@11额77。
 */
class AveragePriceCalculatorTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    @Test
    void inboundOnEmptyStockSetsAverageToInboundPrice() {
        AveragePriceCalculator.InResult r =
                AveragePriceCalculator.applyIn(StockState.zero(), bd("10"), bd("10"));

        assertState(r.next(), "10", "100", "10");
        assertThat(r.costAmountDelta()).isEqualByComparingTo(bd("100"));
    }

    @Test
    void secondInboundRecomputesAverage() {
        StockState after = AveragePriceCalculator
                .applyIn(StockState.zero(), bd("10"), bd("10")).next();

        AveragePriceCalculator.InResult r = AveragePriceCalculator.applyIn(after, bd("5"), bd("13"));

        // 165/15 = 11
        assertState(r.next(), "15", "165", "11");
        assertThat(r.costAmountDelta()).isEqualByComparingTo(bd("65"));
    }

    @Test
    void outboundAtAverageCarriesCostAndKeepsPrice() {
        StockState s = new StockState(bd("15"), bd("165"), bd("11"));

        AveragePriceCalculator.OutResult r = AveragePriceCalculator.applyOut(s, bd("8"));

        // 结转 8×11=88；余 7，额 77，均价不变
        assertThat(r.carryAmount()).isEqualByComparingTo(bd("88"));
        assertState(r.next(), "7", "77", "11");
    }

    @Test
    void clearingSweepsRoundingResidue() {
        // 入10@10.05 → 入5@13 → 均价 11.033333，全出 15：
        // 按均价结转 = 15×11.033333 = 165.50(=全额)，但规则要求出清直接取 cost_amount 全额，
        // 任何尾差（此处构造为 0.01 级残差场景的泛化）必须归零
        StockState s1 = AveragePriceCalculator.applyIn(StockState.zero(), bd("10"), bd("10.05")).next();
        StockState s2 = AveragePriceCalculator.applyIn(s1, bd("5"), bd("13")).next();
        assertState(s2, "15", "165.50", "11.033333");

        AveragePriceCalculator.OutResult r = AveragePriceCalculator.applyOut(s2, bd("15"));

        assertThat(r.carryAmount()).isEqualByComparingTo(bd("165.50"));
        assertState(r.next(), "0", "0", "0");
    }

    @Test
    void zeroPriceInboundKeepsAmountAndDilutesPrice() {
        StockState s = new StockState(bd("10"), bd("100"), bd("10"));

        StockState next = AveragePriceCalculator.applyIn(s, bd("5"), Z).next();

        // 金额不变 100，量 15，均价 6.666667（HALF_UP 6 位）
        assertState(next, "15", "100", "6.666667");
    }

    @Test
    void pureFunctionDoesNotValidateOverdraw() {
        // 超出不拦：调用方（StockService）负责校验，纯函数只算数
        StockState s = new StockState(bd("5"), bd("50"), bd("10"));

        AveragePriceCalculator.OutResult r = AveragePriceCalculator.applyOut(s, bd("8"));

        assertThat(r.carryAmount()).isEqualByComparingTo(bd("80"));
        assertState(r.next(), "-3", "-30", "10");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertState(StockState s, String qty, String amount, String price) {
        assertThat(s.qty()).isEqualByComparingTo(bd(qty));
        assertThat(s.costAmount()).isEqualByComparingTo(bd(amount));
        assertThat(s.costPrice()).isEqualByComparingTo(bd(price));
    }
}
