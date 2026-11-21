package com.wille.erp.sale.service;

import cn.dev33.satoken.stp.StpUtil;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.LockCmd;
import com.wille.erp.inventory.api.StockCmd.LockLine;
import com.wille.erp.inventory.api.StockCmd.LockRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.support.DataFactory;
import com.wille.erp.support.StockInvariantAsserter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可用量查询（M5 任务 1，spec §5）：订单/出库录入实时提示 = qty − locked_qty。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SaleAvailabilityServiceTest {

    @Autowired
    private SaleAvailabilityService availabilityService;
    @Autowired
    private StockService stockService;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private StockInvariantAsserter asserter;

    @BeforeEach
    void bindAdmin() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), new MockHttpServletResponse()));
        StpUtil.login(1L);
    }

    @AfterEach
    void clearLogin() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void availableSubtractsLocked() {
        BaseWarehouse wh = dataFactory.warehouse("SAV-W1");
        BaseProduct p = dataFactory.product("SAV-P1", com.wille.erp.common.enums.MgmtMode.NONE);
        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 1L, "SAV-IN"),
                List.of(new MoveLine(p.getId(), new BigDecimal("10"), new BigDecimal("5"), null, null, 1L))));
        stockService.lock(new LockCmd(wh.getId(), new LockRef(DocType.SAL_ORDER, 999L, 998L),
                List.of(new LockLine(p.getId(), new BigDecimal("4")))));

        Map<Long, BigDecimal> result = availabilityService.availability(wh.getId(), List.of(p.getId()));

        assertThat(result.get(p.getId())).isEqualByComparingTo(new BigDecimal("6"));
        asserter.assertConsistent();
    }

    @Test
    void missingSkuRowReportsZero() {
        BaseWarehouse wh = dataFactory.warehouse("SAV-W2");
        BaseProduct p = dataFactory.product("SAV-P2", com.wille.erp.common.enums.MgmtMode.NONE);

        Map<Long, BigDecimal> result = availabilityService.availability(wh.getId(), List.of(p.getId()));

        assertThat(result.get(p.getId())).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
