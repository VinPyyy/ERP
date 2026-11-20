package com.wille.erp.support;

import com.wille.erp.inventory.dto.ReplayReportVO;
import com.wille.erp.inventory.service.StockReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账器断言工具（spec §6 测试基建）：委托 StockReconcileService 做四层不变量
 * （批次 SUM / SN 计数 / 锁定汇总 / 流水重放链+终态），diffs 必须为空。
 * 库存相关测试收尾必跑。
 */
@Component
@RequiredArgsConstructor
public class StockInvariantAsserter {

    private final StockReconcileService reconcileService;

    public void assertConsistent() {
        ReplayReportVO report = reconcileService.check();
        assertThat(report.diffs()).as("库存四层不变量/重放差异").isEmpty();
    }
}
