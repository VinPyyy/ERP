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
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.entity.InvStockLock;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockLockMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.sale.dto.OutboundItemRequest;
import com.wille.erp.sale.dto.OutboundSaveRequest;
import com.wille.erp.sale.dto.SaleOrderSaveRequest;
import com.wille.erp.sale.entity.SalOrder;
import com.wille.erp.sale.entity.SalOrderItem;
import com.wille.erp.sale.entity.SalOutbound;
import com.wille.erp.sale.entity.SalOutboundItem;
import com.wille.erp.sale.event.SaleOutboundAuditedEvent;
import com.wille.erp.sale.mapper.SalOrderItemMapper;
import com.wille.erp.sale.mapper.SalOrderMapper;
import com.wille.erp.sale.mapper.SalOutboundItemMapper;
import com.wille.erp.sale.mapper.SalOutboundMapper;
import com.wille.erp.support.CountLockFixture;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 销售出库（M5 任务 4，spec §4.1/§8-5）：锁定转实扣、超发严禁、直接出库可用量、
 * SN/批次出库方向规则、作废重锁冲正。每例锁定不变量 + 四层对账。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(SaleOutboundServiceTest.EventCapture.class)
class SaleOutboundServiceTest {

    @TestConfiguration
    static class EventCapture {
        static final List<SaleOutboundAuditedEvent> CAPTURED = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(SaleOutboundAuditedEvent event) {
            CAPTURED.add(event);
        }
    }

    @Autowired
    private SaleOutboundService outboundService;
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
    private InvSnMapper snMapper;
    @Autowired
    private StockInvariantAsserter asserter;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private ConfigService configService;
    @Autowired
    private CountLockFixture countLockFixture;
    @Autowired
    private com.wille.erp.inventory.api.LotApi lotApi;

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

    private record Ctx(BaseWarehouse wh, BaseCustomer cu, BaseProduct p) {
    }

    private Ctx ctx(String tag, String stockQty, MgmtMode mode) {
        BaseWarehouse wh = dataFactory.warehouse("OB-W" + tag);
        BaseCustomer cu = dataFactory.customer("OB-C" + tag);
        BaseProduct p = dataFactory.product("OB-P" + tag, mode);
        if (stockQty != null) {
            stockService.inbound(new MoveCmd(wh.getId(),
                    new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 1L, "OB-IN"),
                    List.of(new MoveLine(p.getId(), bd(stockQty), bd("5"), null, null, 1L))));
        }
        return new Ctx(wh, cu, p);
    }

    /** 单行订单并审核锁定 */
    private Long auditedOrder(Ctx c, String qty) {
        SaleOrderSaveRequest req = new SaleOrderSaveRequest();
        req.setCustomerId(c.cu().getId());
        req.setWarehouseId(c.wh().getId());
        var line = new com.wille.erp.sale.dto.SaleOrderItemRequest();
        line.setSkuId(c.p().getId());
        line.setQty(bd(qty));
        line.setPrice(bd("9"));
        req.setItems(List.of(line));
        Long id = orderService.create(req);
        orderService.audit(id);
        return id;
    }

    private SalOrderItem orderLine(Long orderId) {
        return orderItemMapper.selectList(Wrappers.<SalOrderItem>lambdaQuery()
                .eq(SalOrderItem::getDocId, orderId)).get(0);
    }

    private OutboundSaveRequest sourcedReq(Ctx c, Long orderId, String qty) {
        OutboundSaveRequest req = new OutboundSaveRequest();
        req.setCustomerId(c.cu().getId());
        req.setWarehouseId(c.wh().getId());
        req.setSourceOrderId(orderId);
        OutboundItemRequest line = new OutboundItemRequest();
        line.setSourceOrderItemId(orderLine(orderId).getId());
        line.setSkuId(c.p().getId());
        line.setQty(bd(qty));
        line.setPrice(bd("9"));
        req.setItems(List.of(line));
        return req;
    }

    private OutboundSaveRequest directReq(Ctx c, String qty) {
        OutboundSaveRequest req = new OutboundSaveRequest();
        req.setCustomerId(c.cu().getId());
        req.setWarehouseId(c.wh().getId());
        OutboundItemRequest line = new OutboundItemRequest();
        line.setSkuId(c.p().getId());
        line.setQty(bd(qty));
        line.setPrice(bd("9"));
        req.setItems(List.of(line));
        return req;
    }

    private InvStock stockRow(Ctx c) {
        return stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, c.wh().getId()).eq(InvStock::getSkuId, c.p().getId()));
    }

    @Test
    void auditSourcedReleasesLockAndMovesStock() {
        Ctx c = ctx("1", "10", MgmtMode.NONE);
        Long orderId = auditedOrder(c, "10");
        Long obId = outboundService.create(sourcedReq(c, orderId, "10"));
        // 部分出库：草稿行改 4（模拟用户编辑）
        SalOutboundItem obLine = outboundItemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, obId)).get(0);
        obLine.setQty(bd("4"));
        outboundItemMapper.updateById(obLine);

        outboundService.audit(obId);

        InvStock row = stockRow(c);
        assertThat(row.getQty()).isEqualByComparingTo(bd("6"));
        assertThat(row.getLockedQty()).isEqualByComparingTo(bd("6"));
        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(bd("4"));
        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(10);
        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(DocStatus.AUDITED);
        assertThat(outboundMapper.selectById(obId).getStatus()).isEqualTo(DocStatus.AUDITED);
        assertThat(SaleOutboundServiceTest.EventCapture.CAPTURED.stream()
                .filter(e -> e.getDocId().equals(obId)).toList()).hasSize(1);
        asserter.assertConsistent();
    }

    @Test
    void overShipBoundaryAndCumulative() {
        // 审查重点 3：=剩余放行；剩余+0.0001 拒 50001；分批累计不穿
        Ctx c = ctx("2", "10", MgmtMode.NONE);
        Long orderId = auditedOrder(c, "10");
        Long ob1 = outboundService.create(sourcedReq(c, orderId, "10.0001"));
        assertThatThrownBy(() -> outboundService.audit(ob1))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.EXCEED_ORDER_REMAIN);

        Long ob2 = outboundService.create(sourcedReq(c, orderId, "6"));
        outboundService.audit(ob2);
        Long ob3 = outboundService.create(sourcedReq(c, orderId, "5")); // 6+5 > 10
        assertThatThrownBy(() -> outboundService.audit(ob3))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.EXCEED_ORDER_REMAIN);
        // 预检拒后锁与 done 未被侵蚀
        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(bd("6"));
        asserter.assertConsistent();
    }

    @Test
    void warehouseMismatchSourceRejected() {
        Ctx c = ctx("3", "10", MgmtMode.NONE);
        Long orderId = auditedOrder(c, "10");
        OutboundSaveRequest req = sourcedReq(c, orderId, "5");
        req.setWarehouseId(dataFactory.warehouse("OB-W3X").getId());

        assertThatThrownBy(() -> outboundService.create(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.WAREHOUSE_MISMATCH_SOURCE);
    }

    @Test
    void directOutboundChecksAvailableAndNegativeMatrix() {
        Ctx c = ctx("4", "5", MgmtMode.NONE);
        Long obId = outboundService.create(directReq(c, "6"));
        assertThatThrownBy(() -> outboundService.audit(obId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);

        configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "true");
        Long ob2 = outboundService.create(directReq(c, "6"));
        outboundService.audit(ob2);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("-1"));
        asserter.assertConsistent();
    }

    @Test
    void partialShipThenLineAndHeaderComplete() {
        Ctx c = ctx("5", "10", MgmtMode.NONE);
        Long orderId = auditedOrder(c, "10");
        outboundService.audit(outboundService.create(sourcedReq(c, orderId, "4")));
        Long ob2 = outboundService.create(sourcedReq(c, orderId, "6"));
        outboundService.audit(ob2);

        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(bd("10"));
        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(30);
        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(DocStatus.COMPLETED);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stockRow(c).getLockedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        asserter.assertConsistent();
    }

    @Test
    void voidOutboundRelocksAndRollsBack() {
        Ctx c = ctx("6", "10", MgmtMode.NONE);
        Long orderId = auditedOrder(c, "10");
        Long obId = outboundService.create(sourcedReq(c, orderId, "10"));
        outboundService.audit(obId);
        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(DocStatus.COMPLETED);

        // 审查重点 2 重审结论：reverse 先回补 qty，重锁可用量恒充足——本例同时验证该数学性质
        outboundService.voidOutbound(obId, "发错客户");

        assertThat(outboundMapper.selectById(obId).getStatus()).isEqualTo(DocStatus.VOIDED);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("10"));
        assertThat(stockRow(c).getLockedQty()).isEqualByComparingTo(bd("10"));
        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(10);
        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(DocStatus.AUDITED);
        // 重锁行：该订单行 remain>0 且 =10
        List<InvStockLock> live = lockMapper.selectList(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getLockDocId, orderId).gt(InvStockLock::getRemainQty, BigDecimal.ZERO));
        assertThat(live).hasSize(1);
        assertThat(live.get(0).getRemainQty()).isEqualByComparingTo(bd("10"));
        asserter.assertConsistent();
    }

    @Test
    void partialShipVoidThenReOutboundWorks() {
        // 审查 C2：部分出库→作废→再出库（重锁必须恢复原锁行而非新插行，否则 release 多行守卫堵死后续履约）
        Ctx c = ctx("12", "10", MgmtMode.NONE);
        Long orderId = auditedOrder(c, "10");
        Long ob1 = outboundService.create(sourcedReq(c, orderId, "4"));
        outboundService.audit(ob1);
        outboundService.voidOutbound(ob1, "发错批次");

        Long ob2 = outboundService.create(sourcedReq(c, orderId, "6"));
        outboundService.audit(ob2); // 旧实现此处 release 命中两行 → 10001

        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(bd("6"));
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("4"));
        assertThat(stockRow(c).getLockedQty()).isEqualByComparingTo(bd("4"));
        List<InvStockLock> live = lockMapper.selectList(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getLockDocId, orderId).gt(InvStockLock::getRemainQty, BigDecimal.ZERO));
        assertThat(live).hasSize(1); // 单 ref 单行纪律
        assertThat(live.get(0).getRemainQty()).isEqualByComparingTo(bd("4"));
        asserter.assertConsistent();
    }

    @Test
    void voidOutboundOnClosedLineDoesNotRelock() {
        // 审查 I3：行已关闭(40)时作废出库——库存回补但不重锁，关闭行不得复活锁定
        Ctx c = ctx("13", "10", MgmtMode.NONE);
        Long orderId = auditedOrder(c, "10");
        Long ob1 = outboundService.create(sourcedReq(c, orderId, "4"));
        outboundService.audit(ob1); // remain 6 done 4
        orderService.closeItem(orderId, orderLine(orderId).getId()); // 40，释放 6

        outboundService.voidOutbound(ob1, "关行后收尾");

        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("10"));
        assertThat(stockRow(c).getLockedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lockMapper.selectCount(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getLockDocId, orderId).gt(InvStockLock::getRemainQty, BigDecimal.ZERO))).isZero();
        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(40);
        asserter.assertConsistent();
    }

    @Test
    void warehouseScopeOnPageAndAudit() {
        // 审查 I4：出库单数据权限覆盖（page 过滤 + 跨仓审核拒）
        Ctx a = ctx("14A", "10", MgmtMode.NONE);
        Ctx b = ctx("14B", "10", MgmtMode.NONE);
        Long obA = outboundService.create(directReq(a, "1"));
        Long obB = outboundService.create(directReq(b, "1"));
        assertThat(obA).isNotEqualTo(obB);

        dataFactory.roleWithPerms("R_OBSCOPE", "出库范围", "sale:outbound:list");
        var u = dataFactory.user("obscope01", "pwd123", "R_OBSCOPE");
        dataFactory.bindWarehouses(u.getId(), a.wh().getId());
        StpUtil.logout();
        StpUtil.login(u.getId());

        assertThat(outboundService.page(1, 20, null).getRecords())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.getWarehouseId()).isEqualTo(a.wh().getId()));
        assertThatThrownBy(() -> outboundService.audit(obB))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NO_PERMISSION);
    }

    @Test
    void countLockBlocksAudit() {
        Ctx c = ctx("7", "10", MgmtMode.NONE);
        Long obId = outboundService.create(directReq(c, "3"));
        countLockFixture.counting(c.wh().getId(), c.p().getId());

        assertThatThrownBy(() -> outboundService.audit(obId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.COUNT_LOCK_CONFLICT);
    }

    @Test
    void draftVoidAllowedNoStockEffect() {
        Ctx c = ctx("8", "10", MgmtMode.NONE);
        Long obId = outboundService.create(directReq(c, "3"));

        outboundService.voidOutbound(obId, "重复录入");

        assertThat(outboundMapper.selectById(obId).getStatus()).isEqualTo(DocStatus.VOIDED);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("10"));
        asserter.assertConsistent();
    }

    @Test
    void sourceBindingTamperRejectedAtAudit() {
        // 行 sourceOrderItemId 指向他单订单行 → 审核拒（I3 语义）
        Ctx c = ctx("9", "20", MgmtMode.NONE);
        Long orderA = auditedOrder(c, "10");
        Long orderB = auditedOrder(c, "4");
        Long obId = outboundService.create(sourcedReq(c, orderA, "2"));
        SalOutboundItem obLine = outboundItemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, obId)).get(0);
        obLine.setSourceOrderItemId(orderLine(orderB).getId());
        outboundItemMapper.updateById(obLine);

        assertThatThrownBy(() -> outboundService.audit(obId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("源订单行");
    }

    @Test
    void snOutboundFlipsOutWithDocMarker() {
        Ctx c = ctx("10", null, MgmtMode.SN);
        stockService.inbound(new MoveCmd(c.wh().getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 2L, "OB-SNIN"),
                List.of(new MoveLine(c.p().getId(), bd("2"), bd("5"), null, List.of("OB-A", "OB-B"), 9L))));
        OutboundSaveRequest req = directReq(c, "2");
        req.getItems().get(0).setSnList(List.of("OB-A", "OB-B"));
        Long obId = outboundService.create(req);

        outboundService.audit(obId);

        List<InvSn> outs = snMapper.selectList(Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSkuId, c.p().getId()).eq(InvSn::getStatus, SnStatus.OUT));
        assertThat(outs).hasSize(2);
        assertThat(outs).allSatisfy(s -> assertThat(s.getOutDocId()).isEqualTo(obId));
        asserter.assertConsistent();
    }

    @Test
    void lotOutboundResolvesExistingLotOnly() {
        // 出库方向禁造批次：未知批号 → 拒；既有批号 → 正常出
        Ctx c = ctx("11", null, MgmtMode.LOT);
        Long lotId = lotApi.materialize(c.p().getId(), "OB-LOT-1", java.time.LocalDate.now());
        stockService.inbound(new MoveCmd(c.wh().getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 3L, "OB-LOTIN"),
                List.of(new MoveLine(c.p().getId(), bd("5"), bd("5"), lotId, null, 9L))));

        Long obBad = outboundService.create(directReq(c, "2"));
        SalOutboundItem badLine = outboundItemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, obBad)).get(0);
        badLine.setLotNo("OB-NOPE");
        outboundItemMapper.updateById(badLine);
        assertThatThrownBy(() -> outboundService.audit(obBad))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("批次不存在");

        Long obOk = outboundService.create(directReq(c, "2"));
        SalOutboundItem okLine = outboundItemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, obOk)).get(0);
        okLine.setLotNo("OB-LOT-1");
        outboundItemMapper.updateById(okLine);
        outboundService.audit(obOk);

        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("3"));
        SalOutboundItem audited = outboundItemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, obOk)).get(0);
        assertThat(audited.getLotId()).isEqualTo(lotId);
        asserter.assertConsistent();
    }
}
