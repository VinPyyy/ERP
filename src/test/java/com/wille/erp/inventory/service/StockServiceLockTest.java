package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.LockCmd;
import com.wille.erp.inventory.api.StockCmd.LockLine;
import com.wille.erp.inventory.api.StockCmd.LockRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.entity.InvStockLock;
import com.wille.erp.inventory.mapper.InvStockLockMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.support.DataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单锁定双层结构（spec §3e，M3a 任务 4）：明细 inv_stock_lock + 汇总 locked_qty，
 * 不变量 locked_qty = SUM(remain_qty)。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StockServiceLockTest {

    @Autowired
    private StockService stockService;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private InvStockMapper stockMapper;
    @Autowired
    private InvStockLockMapper lockMapper;

    private static final BizRef IN_REF = new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 999L, "LOCK-IN");
    private static final LockRef ORDER_REF = new LockRef(DocType.SAL_ORDER, 1L, 11L);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private InvStock stock(Long wh, Long sku) {
        return stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, wh).eq(InvStock::getSkuId, sku));
    }

    private void inbound(Long wh, Long sku, String qty) {
        stockService.inbound(new MoveCmd(wh, IN_REF, List.of(
                new MoveLine(sku, bd(qty), bd("10"), null, null, 1L))));
    }

    @Test
    void lockAddsDetailAndSummary() {
        BaseWarehouse wh = dataFactory.warehouse("WH-LK1");
        BaseProduct p = dataFactory.product("SKU-LK1", MgmtMode.NONE);
        inbound(wh.getId(), p.getId(), "10");

        stockService.lock(new LockCmd(wh.getId(), ORDER_REF, List.of(new LockLine(p.getId(), bd("6")))));

        InvStock s = stock(wh.getId(), p.getId());
        assertThat(s.getLockedQty()).isEqualByComparingTo(bd("6"));
        assertThat(stockService.available(wh.getId(), p.getId())).isEqualByComparingTo(bd("4"));

        InvStockLock lock = lockRow(ORDER_REF);
        assertThat(lock.getLockQty()).isEqualByComparingTo(bd("6"));
        assertThat(lock.getRemainQty()).isEqualByComparingTo(bd("6"));
        assertThat(lock.getLockDocType()).isEqualTo("SAL_ORDER");
    }

    @Test
    void unlockReleasesRemainKeepingHistoryRow() {
        BaseWarehouse wh = dataFactory.warehouse("WH-LK2");
        BaseProduct p = dataFactory.product("SKU-LK2", MgmtMode.NONE);
        inbound(wh.getId(), p.getId(), "10");
        stockService.lock(new LockCmd(wh.getId(), ORDER_REF, List.of(new LockLine(p.getId(), bd("6")))));

        stockService.unlock(ORDER_REF);

        assertThat(stock(wh.getId(), p.getId()).getLockedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        InvStockLock lock = lockRow(ORDER_REF);
        assertThat(lock.getRemainQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lock.getLockQty()).isEqualByComparingTo(bd("6")); // 行保留作历史
    }

    @Test
    void lockRejectsWhenAnyLineInsufficientBeforeTouchingOthers() {
        BaseWarehouse wh = dataFactory.warehouse("WH-LK3");
        BaseProduct small = dataFactory.product("SKU-LK3A", MgmtMode.NONE); // skuId 升序在前，先失败
        BaseProduct big = dataFactory.product("SKU-LK3B", MgmtMode.NONE);
        inbound(wh.getId(), small.getId(), "5");
        inbound(wh.getId(), big.getId(), "10");

        assertThatThrownBy(() -> stockService.lock(new LockCmd(wh.getId(),
                new LockRef(DocType.SAL_ORDER, 2L, 22L),
                List.of(new LockLine(small.getId(), bd("8")), new LockLine(big.getId(), bd("8"))))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可用量不足");

        // 失败行在前（skuId 升序处理）：后面的行未被部分锁定污染；整事务回滚由 @Transactional 保证
        assertThat(stock(wh.getId(), big.getId()).getLockedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lockMapper.selectCount(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getLockDocId, 2L))).isZero();
    }

    @Test
    void lockedInvariantAfterMix() {
        BaseWarehouse wh = dataFactory.warehouse("WH-LK4");
        BaseProduct p = dataFactory.product("SKU-LK4", MgmtMode.NONE);
        inbound(wh.getId(), p.getId(), "20");

        stockService.lock(new LockCmd(wh.getId(), new LockRef(DocType.SAL_ORDER, 3L, 31L),
                List.of(new LockLine(p.getId(), bd("7")))));
        stockService.lock(new LockCmd(wh.getId(), new LockRef(DocType.SAL_ORDER, 3L, 32L),
                List.of(new LockLine(p.getId(), bd("5")))));
        stockService.unlock(new LockRef(DocType.SAL_ORDER, 3L, 31L));

        List<InvStockLock> rows = lockMapper.selectList(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getWarehouseId, wh.getId()).eq(InvStockLock::getSkuId, p.getId()));
        BigDecimal sum = rows.stream().map(InvStockLock::getRemainQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        InvStock s = stock(wh.getId(), p.getId());
        assertThat(s.getLockedQty()).isEqualByComparingTo(sum);
        assertThat(s.getLockedQty()).isEqualByComparingTo(bd("5"));
    }

    @Test
    void lockRejectsNonPositiveQty() {
        // 审查 Important-8：负数锁定会静默击穿可用量且骗过对账器
        BaseWarehouse wh = dataFactory.warehouse("WH-LK5");
        BaseProduct p = dataFactory.product("SKU-LK5", MgmtMode.NONE);
        inbound(wh.getId(), p.getId(), "10");

        assertThatThrownBy(() -> stockService.lock(new LockCmd(wh.getId(),
                new LockRef(DocType.SAL_ORDER, 5L, 51L), List.of(new LockLine(p.getId(), bd("-5"))))))
                .isInstanceOf(BizException.class);
    }

    @Test
    void releasePartiallyConsumesLock() {
        // 审查 Important-3：§3e.2 锁定转实扣 = 部分核销，门面缺 release 无法表达
        BaseWarehouse wh = dataFactory.warehouse("WH-LK6");
        BaseProduct p = dataFactory.product("SKU-LK6", MgmtMode.NONE);
        inbound(wh.getId(), p.getId(), "10");
        LockRef ref = new LockRef(DocType.SAL_ORDER, 6L, 61L);
        stockService.lock(new LockCmd(wh.getId(), ref, List.of(new LockLine(p.getId(), bd("6")))));

        stockService.release(ref, bd("4"));

        assertThat(stock(wh.getId(), p.getId()).getLockedQty()).isEqualByComparingTo(bd("2"));
        assertThat(lockRow(ref).getRemainQty()).isEqualByComparingTo(bd("2"));
        assertThat(lockRow(ref).getLockQty()).isEqualByComparingTo(bd("6"));

        assertThatThrownBy(() -> stockService.release(ref, bd("3")))
                .isInstanceOf(BizException.class);
    }

    private InvStockLock lockRow(LockRef ref) {
        return lockMapper.selectOne(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getLockDocType, ref.lockDocType().name())
                .eq(InvStockLock::getLockDocId, ref.lockDocId())
                .eq(InvStockLock::getLockItemId, ref.lockItemId()));
    }
}
