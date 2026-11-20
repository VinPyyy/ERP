package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.entity.InvFlow;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvFlowMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.support.DataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发首行 upsert（审查重点 2）：两个真实事务同时首次入库同一 (wh,sku)，
 * 撞 UNIQUE 后必须重试而非丢数据或 500。本类刻意不加 @Transactional——
 * 测试事务会让 FOR UPDATE/唯一键语义失真。
 */
@SpringBootTest
@ActiveProfiles("test")
class StockServiceConcurrencyTest {

    @Autowired
    private StockService stockService;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private InvStockMapper stockMapper;
    @Autowired
    private InvFlowMapper flowMapper;

    private Long testSkuId;

    @AfterEach
    void cleanup() {
        if (testSkuId == null) {
            return;
        }
        stockMapper.delete(Wrappers.<InvStock>lambdaQuery().eq(InvStock::getSkuId, testSkuId));
        flowMapper.delete(Wrappers.<InvFlow>lambdaQuery().eq(InvFlow::getSkuId, testSkuId));
    }

    @Test
    void concurrentFirstInboundUpsertSafe() throws Exception {
        // 本类无测试事务回滚，编码必须每次运行唯一
        String suffix = Long.toString(System.nanoTime());
        BaseWarehouse wh = dataFactory.warehouse("WHC" + suffix);
        BaseProduct p = dataFactory.product("SKUC" + suffix, MgmtMode.NONE);
        testSkuId = p.getId();

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable task = () -> {
            try {
                start.await();
                stockService.inbound(new MoveCmd(wh.getId(),
                        new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 1L, "CONC"),
                        List.of(new MoveLine(p.getId(), new BigDecimal("5"), new BigDecimal("10"),
                                null, null, 1L))));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(30_000);
        t2.join(30_000);

        assertThat(failure.get()).isNull();
        InvStock s = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, wh.getId()).eq(InvStock::getSkuId, p.getId()));
        assertThat(s.getQty()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(s.getCostAmount()).isEqualByComparingTo(new BigDecimal("100"));
    }
}
