package com.wille.erp.inventory.service;

import com.wille.erp.inventory.dto.ReplayReportVO;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvStockMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账服务盲区测试（审查 Important-10）：绕过门面直接写出的库存行
 * （有量无流水）必须被 final_vs_stock 抓到——"库存行永远等于流水重放终态"。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StockReconcileServiceTest {

    @Autowired
    private StockReconcileService reconcileService;
    @Autowired
    private InvStockMapper stockMapper;

    @Test
    void stockRowWithoutFlowsIsReported() {
        InvStock smuggled = new InvStock();
        smuggled.setWarehouseId(987654L);
        smuggled.setSkuId(123456L);
        smuggled.setQty(new BigDecimal("5"));
        smuggled.setLockedQty(BigDecimal.ZERO);
        smuggled.setCostPrice(BigDecimal.ZERO);
        smuggled.setCostAmount(new BigDecimal("50"));
        stockMapper.insert(smuggled);

        ReplayReportVO report = reconcileService.check();

        assertThat(report.consistent()).isFalse();
        assertThat(report.diffs()).anySatisfy(d -> {
            assertThat(d.scope()).isEqualTo("final_vs_stock");
            assertThat(d.refId()).isEqualTo(smuggled.getId());
        });
    }

    @Test
    void zeroRowPlaceholderWithoutFlowsIsClean() {
        // INSERT IGNORE 占位行（全零、无流水）不得误报
        InvStock placeholder = new InvStock();
        placeholder.setWarehouseId(987655L);
        placeholder.setSkuId(123457L);
        placeholder.setQty(BigDecimal.ZERO);
        placeholder.setLockedQty(BigDecimal.ZERO);
        placeholder.setCostPrice(BigDecimal.ZERO);
        placeholder.setCostAmount(BigDecimal.ZERO);
        stockMapper.insert(placeholder);

        assertThat(reconcileService.check().diffs())
                .noneMatch(d -> d.scope().equals("final_vs_stock")
                        && d.refId().equals(placeholder.getId()));
    }
}
