package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.inventory.core.FlowReplayChecker;
import com.wille.erp.inventory.core.ReplayDiff;
import com.wille.erp.inventory.core.StockState;
import com.wille.erp.inventory.dto.ReplayReportVO;
import com.wille.erp.inventory.entity.InvFlow;
import com.wille.erp.inventory.entity.InvLotStock;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.entity.InvStockLock;
import com.wille.erp.inventory.mapper.InvFlowMapper;
import com.wille.erp.inventory.mapper.InvLotStockMapper;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockLockMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四层不变量对账（spec §3c）：流水重放链与终态、批次 SUM、SN 计数、锁定汇总。
 * 只读，可任意时点调用；property 测试与 replay-check 端点共用。
 */
@Service
@RequiredArgsConstructor
public class StockReconcileService {

    private final InvStockMapper stockMapper;
    private final InvFlowMapper flowMapper;
    private final InvLotStockMapper lotStockMapper;
    private final InvSnMapper snMapper;
    private final InvStockLockMapper lockMapper;
    private final ProductReader productReader;

    /**
     * 只读事务内取一致性快照（审查 Important-9）：无事务时流水与库存行分属
     * 不同 read view，业务并发写入会产生 final_vs_stock 假阳性。
     */
    @Transactional(readOnly = true)
    public ReplayReportVO check() {
        List<ReplayDiff> diffs = new ArrayList<>();
        List<InvStock> stockRows = stockMapper.selectList(null);
        diffs.addAll(replayDiff(stockRows));
        diffs.addAll(lotSumDiff(stockRows));
        diffs.addAll(snCountDiff(stockRows));
        diffs.addAll(lockedSumDiff(stockRows));
        return new ReplayReportVO(diffs.isEmpty(), diffs);
    }

    /**
     * 层 4：每个 (wh,sku) 流水链自洽 + 终态 = inv_stock 行。
     * 迭代域 = 流水键 ∪ 库存行（审查 Important-10）：有量无流水的行——
     * 恰是"绕过门面写库存"这类最需要抓的损坏——必须与零态比对。
     */
    private List<ReplayDiff> replayDiff(List<InvStock> stockRows) {
        List<ReplayDiff> diffs = new ArrayList<>();
        Map<String, List<InvFlow>> byKey = new LinkedHashMap<>();
        for (InvFlow f : flowMapper.selectList(Wrappers.<InvFlow>lambdaQuery().orderByAsc(InvFlow::getId))) {
            byKey.computeIfAbsent(key(f.getWarehouseId(), f.getSkuId()), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<String, List<InvFlow>> e : byKey.entrySet()) {
            diffs.addAll(FlowReplayChecker.replay(e.getValue()));
            InvFlow first = e.getValue().get(0);
            StockState finalState = FlowReplayChecker.replayFinal(e.getValue());
            InvStock row = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                    .eq(InvStock::getWarehouseId, first.getWarehouseId())
                    .eq(InvStock::getSkuId, first.getSkuId()));
            addFinalDiff(diffs, finalState, row);
        }
        for (InvStock row : stockRows) {
            if (!byKey.containsKey(key(row.getWarehouseId(), row.getSkuId()))) {
                addFinalDiff(diffs, StockState.zero(), row);
            }
        }
        return diffs;
    }

    private void addFinalDiff(List<ReplayDiff> diffs, StockState finalState, InvStock row) {
        BigDecimal qty = row == null ? BigDecimal.ZERO : row.getQty();
        BigDecimal amount = row == null ? BigDecimal.ZERO : row.getCostAmount();
        BigDecimal price = row == null ? BigDecimal.ZERO : row.getCostPrice();
        if (finalState.qty().compareTo(qty) != 0
                || finalState.costAmount().compareTo(amount) != 0
                || finalState.costPrice().compareTo(price) != 0) {
            diffs.add(new ReplayDiff("final_vs_stock", row == null ? null : row.getId(),
                    "重放终态 " + finalState + "，库存行 " + qty + "/" + amount + "/" + price));
        }
    }

    private static String key(Long warehouseId, Long skuId) {
        return warehouseId + ":" + skuId;
    }

    /** 层 1：管批次 SKU 的 SUM(批次行.qty) = 主行.qty */
    private List<ReplayDiff> lotSumDiff(List<InvStock> stockRows) {
        List<ReplayDiff> diffs = new ArrayList<>();
        for (InvStock row : stockRows) {
            var product = productReader.read(row.getSkuId());
            if (product == null || !product.lotManaged()) {
                continue;
            }
            BigDecimal sum = lotStockMapper.selectList(Wrappers.<InvLotStock>lambdaQuery()
                            .eq(InvLotStock::getWarehouseId, row.getWarehouseId())
                            .eq(InvLotStock::getSkuId, row.getSkuId())).stream()
                    .map(InvLotStock::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(row.getQty()) != 0) {
                diffs.add(new ReplayDiff("lot_sum", row.getId(), "批次合计 " + sum + "，主行 " + row.getQty()));
            }
        }
        return diffs;
    }

    /** 层 2：管 SN SKU 的 COUNT(在库@仓) = 主行.qty */
    private List<ReplayDiff> snCountDiff(List<InvStock> stockRows) {
        List<ReplayDiff> diffs = new ArrayList<>();
        for (InvStock row : stockRows) {
            var product = productReader.read(row.getSkuId());
            if (product == null || !product.snManaged()) {
                continue;
            }
            long count = snMapper.selectCount(Wrappers.<InvSn>lambdaQuery()
                    .eq(InvSn::getSkuId, row.getSkuId())
                    .eq(InvSn::getWarehouseId, row.getWarehouseId())
                    .eq(InvSn::getStatus, SnStatus.IN_STOCK));
            if (row.getQty().compareTo(BigDecimal.valueOf(count)) != 0) {
                diffs.add(new ReplayDiff("sn_count", row.getId(), "在库SN " + count + "，主行 " + row.getQty()));
            }
        }
        return diffs;
    }

    /** 层 3：locked_qty = SUM(remain_qty) */
    private List<ReplayDiff> lockedSumDiff(List<InvStock> stockRows) {
        List<ReplayDiff> diffs = new ArrayList<>();
        for (InvStock row : stockRows) {
            BigDecimal sum = lockMapper.selectList(Wrappers.<InvStockLock>lambdaQuery()
                            .eq(InvStockLock::getWarehouseId, row.getWarehouseId())
                            .eq(InvStockLock::getSkuId, row.getSkuId())).stream()
                    .map(InvStockLock::getRemainQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(row.getLockedQty()) != 0) {
                diffs.add(new ReplayDiff("locked_sum", row.getId(), "锁定明细合计 " + sum + "，汇总 " + row.getLockedQty()));
            }
        }
        return diffs;
    }
}
