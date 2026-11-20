package com.wille.erp.inventory.core;

import com.wille.erp.inventory.entity.InvFlow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 流水重放纯函数（spec §3c）：库存行必须永远等于流水重放终态。
 * 重放规则=带符号累加（写入侧 applyIn/applyOut 的结果已固化在 cost_amount 里，
 * 冲正行天然入链，无需方向语义）。
 */
public final class FlowReplayChecker {

    private FlowReplayChecker() {
    }

    /** flows 为同一 (warehouse,sku) 的流水；逐行校验 balance_* 快照链 */
    public static List<ReplayDiff> replay(List<InvFlow> flows) {
        List<ReplayDiff> diffs = new ArrayList<>();
        accumulate(flows, (running, f) -> {
            diff(diffs, "balance_qty", f, running.qty(), f.getBalanceQty());
            diff(diffs, "balance_cost_amount", f, running.costAmount(), f.getBalanceCostAmount());
            diff(diffs, "balance_cost_price", f, running.costPrice(), f.getBalanceCostPrice());
        });
        return diffs;
    }

    /** 重放终态（对账服务用它比对 inv_stock 行） */
    public static StockState replayFinal(List<InvFlow> flows) {
        return accumulate(flows, null);
    }

    private static StockState accumulate(List<InvFlow> flows, Step step) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        for (InvFlow f : flows.stream().sorted(Comparator.comparing(InvFlow::getId)).toList()) {
            qty = qty.add(f.getQty());
            amount = amount.add(f.getCostAmount()).setScale(2, RoundingMode.HALF_UP);
            StockState running = new StockState(qty, amount, derivePrice(amount, qty));
            if (step != null) {
                step.accept(running, f);
            }
        }
        return new StockState(qty, amount, derivePrice(amount, qty));
    }

    private interface Step {
        void accept(StockState running, InvFlow flow);
    }

    private static boolean diff(List<ReplayDiff> diffs, String scope, InvFlow f,
                                BigDecimal replayed, BigDecimal snapshot) {
        if (replayed.compareTo(snapshot) != 0) {
            diffs.add(new ReplayDiff(scope, f.getId(),
                    "重放=" + replayed.toPlainString() + "，快照=" + snapshot.toPlainString()));
            return true;
        }
        return false;
    }

    private static BigDecimal derivePrice(BigDecimal amount, BigDecimal qty) {
        return qty.signum() == 0
                ? BigDecimal.ZERO
                : amount.divide(qty, 6, RoundingMode.HALF_UP);
    }
}
