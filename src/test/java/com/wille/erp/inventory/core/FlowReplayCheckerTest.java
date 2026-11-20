package com.wille.erp.inventory.core;

import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.inventory.entity.InvFlow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流水重放对账纯函数（spec §3c/§3f，M3a 任务 5）：
 * 按 (wh,sku) 分组 id 升序，逐行 balance 快照 = 上行快照 + 本行带符号变动。
 */
class FlowReplayCheckerTest {

    private static InvFlow flow(long id, String qty, String costAmount,
                                String balQty, String balAmount, String balPrice) {
        InvFlow f = new InvFlow();
        f.setId(id);
        f.setWarehouseId(1L);
        f.setSkuId(2L);
        f.setQty(new BigDecimal(qty));
        f.setCostAmount(new BigDecimal(costAmount));
        f.setCostPrice(new BigDecimal("10"));
        f.setBalanceQty(new BigDecimal(balQty));
        f.setBalanceCostAmount(new BigDecimal(balAmount));
        f.setBalanceCostPrice(new BigDecimal(balPrice));
        f.setBizType(BizType.PUR_IN);
        f.setBizDocType(DocType.PUR_INBOUND);
        f.setBizDocId(1L);
        f.setBizDocNo("X");
        return f;
    }

    @Test
    void emptyFlowsNoDiff() {
        assertThat(FlowReplayChecker.replay(List.of())).isEmpty();
    }

    @Test
    void consistentChainProducesNoDiff() {
        // 入10@10 → 入5@14(70) → 出8：链自洽
        InvFlow f1 = flow(1, "10", "100", "10", "100", "10");
        InvFlow f2 = flow(2, "5", "70", "15", "170", "11.333333");
        InvFlow f3 = flow(3, "-8", "-90.67", "7", "79.33", "11.332857");
        assertThat(FlowReplayChecker.replay(List.of(f1, f2, f3))).isEmpty();
    }

    @Test
    void snapshotChainBrokenDetected() {
        InvFlow f1 = flow(1, "10", "100", "10", "100", "10");
        InvFlow broken = flow(2, "5", "70", "99", "170", "11.333333"); // balanceQty 写错
        List<ReplayDiff> diffs = FlowReplayChecker.replay(List.of(f1, broken));
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).scope()).isEqualTo("balance_qty");
        assertThat(diffs.get(0).detail()).contains("重放=15", "快照=99");
    }

    @Test
    void reversedEntryParticipatesInReplay() {
        // 入10@10 后被冲正（qty −10、cost_amount −100 快照原额）→ 终态 0/0/0，链应自洽
        InvFlow in = flow(1, "10", "100", "10", "100", "10");
        InvFlow reverse = flow(2, "-10", "-100", "0", "0", "0");
        reverse.setBizType(BizType.PUR_IN);
        reverse.setReverseFlowId(1L);
        assertThat(FlowReplayChecker.replay(List.of(in, reverse))).isEmpty();
    }
}
