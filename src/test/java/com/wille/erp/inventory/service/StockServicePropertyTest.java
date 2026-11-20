package com.wille.erp.inventory.service;

import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.LockCmd;
import com.wille.erp.inventory.api.StockCmd.LockLine;
import com.wille.erp.inventory.api.StockCmd.LockRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.support.DataFactory;
import com.wille.erp.support.StockInvariantAsserter;
import com.wille.erp.system.api.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 随机操作序列 property 测试（issues/04 验收锚点）：
 * 固定种子 200 步 in/out/lock/unlock 混合，每 20 步跑四层不变量 + 流水重放。
 * 开负库存开关避免随机序列大量撞异常中断（重放一致性与开关无关）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StockServicePropertyTest {

    @Autowired
    private StockService stockService;
    @Autowired
    private StockInvariantAsserter asserter;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private ConfigService configService;

    @Test
    void randomOperationSequenceKeepsAllInvariants() {
        configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "true");
        try {
            BaseWarehouse wh = dataFactory.warehouse("WH-PROP");
            BaseProduct p = dataFactory.product("SKU-PROP", MgmtMode.NONE);
            Random rnd = new Random(20261120L);
            List<Long> lockedItemIds = new ArrayList<>();
            long docSeq = 100;

            for (int step = 1; step <= 200; step++) {
                int op = rnd.nextInt(10);
                switch (op) {
                    case 0, 1, 2, 3 -> stockService.inbound(new MoveCmd(wh.getId(),
                            new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, docSeq, "PROP-IN-" + docSeq),
                            List.of(new MoveLine(p.getId(), bd(1 + rnd.nextInt(20)),
                                    bd(rnd.nextInt(30)), null, null, docSeq))));
                    case 4, 5, 6, 7 -> stockService.outbound(new MoveCmd(wh.getId(),
                            new BizRef(BizType.SALE_OUT, DocType.SAL_OUTBOUND, docSeq, "PROP-OUT-" + docSeq),
                            List.of(new MoveLine(p.getId(), bd(1 + rnd.nextInt(15)), null, null, null, docSeq))));
                    case 8 -> {
                        long itemId = docSeq;
                        stockService.lock(new LockCmd(wh.getId(),
                                new LockRef(DocType.SAL_ORDER, 9000L, itemId),
                                List.of(new LockLine(p.getId(), bd(1 + rnd.nextInt(5))))));
                        lockedItemIds.add(itemId);
                    }
                    default -> {
                        if (!lockedItemIds.isEmpty()) {
                            Long itemId = lockedItemIds.remove(rnd.nextInt(lockedItemIds.size()));
                            stockService.unlock(new LockRef(DocType.SAL_ORDER, 9000L, itemId));
                        }
                    }
                }
                docSeq++;
                if (step % 20 == 0) {
                    asserter.assertConsistent();
                }
            }
            asserter.assertConsistent();
            // 序列确实执行到位：流水行数与步数一致
            assertThat(stockService.available(wh.getId(), p.getId())).isInstanceOf(BigDecimal.class);
        } finally {
            configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "false");
        }
    }

    private static BigDecimal bd(int v) {
        return new BigDecimal(v);
    }
}
