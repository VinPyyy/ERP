package com.wille.erp.sale.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseCustomer;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.LockRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.entity.InvStockLock;
import com.wille.erp.inventory.mapper.InvStockLockMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.support.CountLockFixture;
import com.wille.erp.sale.dto.SaleOrderItemRequest;
import com.wille.erp.sale.dto.SaleOrderSaveRequest;
import com.wille.erp.sale.entity.SalOrder;
import com.wille.erp.sale.entity.SalOrderItem;
import com.wille.erp.sale.entity.SalOutbound;
import com.wille.erp.sale.entity.SalOutboundItem;
import com.wille.erp.sale.event.SaleOrderAuditedEvent;
import com.wille.erp.sale.mapper.SalOrderItemMapper;
import com.wille.erp.sale.mapper.SalOrderMapper;
import com.wille.erp.sale.mapper.SalOutboundItemMapper;
import com.wille.erp.sale.mapper.SalOutboundMapper;
import com.wille.erp.support.DataFactory;
import com.wille.erp.support.StockInvariantAsserter;
import com.wille.erp.system.api.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 销售订单（M5 任务 3，spec §3e/§4.1 前置）：审核即锁定（整单成败）、行关闭释放、
 * 作废全额释放、盘点锁接入、create-outbound 带出未履约行。每例锁定不变量断言。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(SaleOrderServiceTest.EventCapture.class)
class SaleOrderServiceTest {

    @TestConfiguration
    static class EventCapture {
        static final java.util.List<SaleOrderAuditedEvent> CAPTURED =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @EventListener
        public void on(SaleOrderAuditedEvent event) {
            CAPTURED.add(event);
        }
    }

    @Autowired
    private SaleOrderService orderService;
    @Autowired
    private SalOrderMapper orderMapper;
    @Autowired
    private SalOrderItemMapper orderItemMapper;
    @Autowired
    private SalOutboundMapper outboundMapper;
    @Autowired
    private SalOutboundItemMapper outboundItemMapper;
    @Autowired
    private StockService stockService;
    @Autowired
    private InvStockMapper stockMapper;
    @Autowired
    private InvStockLockMapper lockMapper;
    @Autowired
    private StockInvariantAsserter asserter;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private ConfigService configService;
    @Autowired
    private CountLockFixture countLockFixture;

    @BeforeEach
    void bindAdmin() {
        EventCapture.CAPTURED.clear();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), new MockHttpServletResponse()));
        StpUtil.login(1L);
    }

    @AfterEach
    void restore() {
        configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "false");
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private record Ctx(BaseWarehouse wh, BaseCustomer cu, BaseProduct p1, BaseProduct p2) {
    }

    private Ctx ctx(String tag, String stockP1, String stockP2) {
        BaseWarehouse wh = dataFactory.warehouse("SO-W" + tag);
        BaseCustomer cu = dataFactory.customer("SO-C" + tag);
        BaseProduct p1 = dataFactory.product("SO-P1" + tag, MgmtMode.NONE);
        BaseProduct p2 = dataFactory.product("SO-P2" + tag, MgmtMode.NONE);
        if (stockP1 != null) {
            stockService.inbound(new MoveCmd(wh.getId(),
                    new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 1L, "SO-IN"),
                    List.of(new MoveLine(p1.getId(), bd(stockP1), bd("5"), null, null, 1L))));
        }
        if (stockP2 != null) {
            stockService.inbound(new MoveCmd(wh.getId(),
                    new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 2L, "SO-IN2"),
                    List.of(new MoveLine(p2.getId(), bd(stockP2), bd("5"), null, null, 2L))));
        }
        return new Ctx(wh, cu, p1, p2);
    }

    private Long order(Ctx c, String qty1, String qty2) {
        SaleOrderSaveRequest req = new SaleOrderSaveRequest();
        req.setCustomerId(c.cu().getId());
        req.setWarehouseId(c.wh().getId());
        SaleOrderItemRequest l1 = new SaleOrderItemRequest();
        l1.setSkuId(c.p1().getId());
        l1.setQty(bd(qty1));
        l1.setPrice(bd("9"));
        SaleOrderItemRequest l2 = new SaleOrderItemRequest();
        l2.setSkuId(c.p2().getId());
        l2.setQty(bd(qty2));
        l2.setPrice(bd("12"));
        req.setItems(List.of(l1, l2));
        return orderService.create(req);
    }

    private SalOrderItem line(Long orderId, int no) {
        return orderItemMapper.selectList(Wrappers.<SalOrderItem>lambdaQuery()
                .eq(SalOrderItem::getDocId, orderId).orderByAsc(SalOrderItem::getLineNo)).get(no - 1);
    }

    private BigDecimal lockedOf(Long whId, Long skuId) {
        InvStock row = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, whId).eq(InvStock::getSkuId, skuId));
        return row == null ? BigDecimal.ZERO : row.getLockedQty();
    }

    @Test
    void auditLocksPerLineAndPublishesEvent() {
        Ctx c = ctx("1", "10", "10");
        Long orderId = order(c, "4", "3");

        orderService.audit(orderId);

        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(DocStatus.AUDITED);
        assertThat(lockedOf(c.wh().getId(), c.p1().getId())).isEqualByComparingTo(bd("4"));
        assertThat(lockedOf(c.wh().getId(), c.p2().getId())).isEqualByComparingTo(bd("3"));
        List<InvStockLock> locks = lockMapper.selectList(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getLockDocType, DocType.SAL_ORDER.name())
                .eq(InvStockLock::getLockDocId, orderId));
        assertThat(locks).hasSize(2);
        assertThat(locks).allSatisfy(l -> assertThat(l.getRemainQty()).isEqualByComparingTo(l.getLockQty()));
        assertThat(EventCapture.CAPTURED.stream()
                .filter(e -> e.getDocId().equals(orderId)).toList()).hasSize(1);
        asserter.assertConsistent();
    }

    @Test
    void auditInsufficientAvailableRollbacksWholeDoc() {
        Ctx c = ctx("2", "10", "1");
        Long orderId = order(c, "4", "3"); // 第二行可用 1 < 3

        assertThatThrownBy(() -> orderService.audit(orderId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);

        // 预检在写锁之前：整单零残留（生产语义由事务回滚兜底，测试语义由预检顺序兜底）
        assertThat(lockMapper.selectCount(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getLockDocId, orderId))).isZero();
        assertThat(lockedOf(c.wh().getId(), c.p1().getId())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void auditCountLockRejected() {
        Ctx c = ctx("3", "10", "10");
        Long orderId = order(c, "4", "3");
        countLockFixture.counting(c.wh().getId(), c.p1().getId());

        assertThatThrownBy(() -> orderService.audit(orderId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.COUNT_LOCK_CONFLICT);
    }

    @Test
    void closeItemReleasesRemainder() {
        Ctx c = ctx("4", "10", "10");
        Long orderId = order(c, "4", "3");
        orderService.audit(orderId);

        orderService.closeItem(orderId, line(orderId, 1).getId());

        assertThat(line(orderId, 1).getLineStatus()).isEqualTo(40);
        assertThat(lockedOf(c.wh().getId(), c.p1().getId())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lockedOf(c.wh().getId(), c.p2().getId())).isEqualByComparingTo(bd("3"));
        assertThatThrownBy(() -> orderService.closeItem(orderId, line(orderId, 1).getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("终态");
        asserter.assertConsistent();
    }

    @Test
    void closeAllItemsCompletesHeader() {
        Ctx c = ctx("5", "10", "10");
        Long orderId = order(c, "4", "3");
        orderService.audit(orderId);

        orderService.closeItem(orderId, line(orderId, 1).getId());
        orderService.closeItem(orderId, line(orderId, 2).getId());

        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(DocStatus.COMPLETED);
    }

    @Test
    void voidOrderReleasesAllLocks() {
        Ctx c = ctx("6", "10", "10");
        Long orderId = order(c, "4", "3");
        orderService.audit(orderId);

        orderService.voidOrder(orderId, "客户取消");

        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(DocStatus.VOIDED);
        assertThat(lockedOf(c.wh().getId(), c.p1().getId())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lockedOf(c.wh().getId(), c.p2().getId())).isEqualByComparingTo(BigDecimal.ZERO);
        asserter.assertConsistent();
    }

    @Test
    void voidOrderBlockedByOpenOutbound() {
        Ctx c = ctx("7", "10", "10");
        Long orderId = order(c, "4", "3");
        orderService.audit(orderId);
        Long outboundId = orderService.createOutbound(orderId);

        assertThatThrownBy(() -> orderService.voidOrder(orderId, "想作废订单"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.VOID_BLOCKED_BY_DOWNSTREAM);
        assertThat(outboundMapper.selectById(outboundId)).isNotNull();
    }

    @Test
    void createOutboundCarriesUnfulfilledLines() {
        Ctx c = ctx("8", "10", "10");
        Long orderId = order(c, "10", "6");
        orderService.audit(orderId);
        // 模拟部分履约：行1 done=4（直接原子累加，出库链在任务 4 实测）
        assertThat(orderItemMapper.addDoneQty(line(orderId, 1).getId(), bd("4"))).isEqualTo(1);

        Long outboundId = orderService.createOutbound(orderId);

        SalOutbound outbound = outboundMapper.selectById(outboundId);
        assertThat(outbound.getStatus()).isEqualTo(DocStatus.DRAFT);
        assertThat(outbound.getSourceOrderId()).isEqualTo(orderId);
        assertThat(outbound.getWarehouseId()).isEqualTo(c.wh().getId());
        assertThat(outbound.getCustomerId()).isEqualTo(c.cu().getId());
        var outLines = outboundItemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, outboundId).orderByAsc(SalOutboundItem::getLineNo));
        assertThat(outLines).hasSize(2);
        assertThat(outLines.get(0).getQty()).isEqualByComparingTo(bd("6")); // 10 − done 4
        assertThat(outLines.get(0).getSourceOrderItemId()).isEqualTo(line(orderId, 1).getId());
        assertThat(outLines.get(0).getPrice()).isEqualByComparingTo(bd("9"));
        assertThat(outLines.get(1).getQty()).isEqualByComparingTo(bd("6"));
        asserter.assertConsistent();
    }

    @Test
    void allowNegativeLockMatrix() {
        Ctx c = ctx("9", "2", null);
        Long orderId = order(c, "4", "3"); // 超实存
        configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "true");

        orderService.audit(orderId);

        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(DocStatus.AUDITED);
        assertThat(lockedOf(c.wh().getId(), c.p1().getId())).isEqualByComparingTo(bd("4"));
        asserter.assertConsistent(); // locked = SUM(remain) 仍成立
    }

    @Test
    void warehouseScopeOnPageAndAudit() {
        Ctx a = ctx("10A", "10", "10");
        Ctx b = ctx("10B", "10", "10");
        Long orderA = order(a, "1", "1");
        Long orderB = order(b, "1", "1");

        dataFactory.roleWithPerms("R_SOSCOPE", "销售订单范围", "sale:order:list");
        var u = dataFactory.user("soscope01", "pwd123", "R_SOSCOPE");
        dataFactory.bindWarehouses(u.getId(), a.wh().getId());
        StpUtil.logout();
        StpUtil.login(u.getId());

        var records = orderService.page(1, 20, null).getRecords();
        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(r -> assertThat(r.getWarehouseId()).isEqualTo(a.wh().getId()));
        assertThatThrownBy(() -> orderService.audit(orderB))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NO_PERMISSION);
    }
}
