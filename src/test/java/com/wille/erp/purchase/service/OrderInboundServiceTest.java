package com.wille.erp.purchase.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.entity.InvCount;
import com.wille.erp.inventory.entity.InvCountItem;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvCountItemMapper;
import com.wille.erp.inventory.mapper.InvCountMapper;
import com.wille.erp.inventory.mapper.InvLotMapper;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.purchase.dto.DocOptionVO;
import com.wille.erp.purchase.dto.InboundItemRequest;
import com.wille.erp.purchase.dto.InboundSaveRequest;
import com.wille.erp.purchase.dto.OrderItemRequest;
import com.wille.erp.purchase.dto.OrderSaveRequest;
import com.wille.erp.purchase.entity.PurInbound;
import com.wille.erp.purchase.entity.PurInboundItem;
import com.wille.erp.purchase.entity.PurOrder;
import com.wille.erp.purchase.entity.PurOrderItem;
import com.wille.erp.purchase.mapper.PurInboundItemMapper;
import com.wille.erp.purchase.mapper.PurInboundMapper;
import com.wille.erp.purchase.mapper.PurOrderItemMapper;
import com.wille.erp.purchase.mapper.PurOrderMapper;
import com.wille.erp.support.DataFactory;
import com.wille.erp.support.StockInvariantAsserter;
import com.wille.erp.system.api.ConfigService;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采购订单 + 采购入库（M4 任务 3，spec §2.4/§4.1/§8-11/12/13）。
 * 溢收边界、分批履约、仓库硬校验、盘点锁、作废逆序与 done_qty 回减。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderInboundServiceTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private InboundService inboundService;
    @Autowired
    private PurOrderMapper orderMapper;
    @Autowired
    private PurOrderItemMapper orderItemMapper;
    @Autowired
    private PurInboundMapper inboundMapper;
    @Autowired
    private PurInboundItemMapper inboundItemMapper;
    @Autowired
    private InvStockMapper stockMapper;
    @Autowired
    private InvLotMapper lotMapper;
    @Autowired
    private InvSnMapper snMapper;
    @Autowired
    private InvCountMapper countMapper;
    @Autowired
    private InvCountItemMapper countItemMapper;
    @Autowired
    private StockInvariantAsserter asserter;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private ConfigService configService;

    @AfterEach
    void restoreRatio() {
        configService.update(ConfigService.KEY_OVER_RECEIVE_RATIO, "0");
    }

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

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private Long order(BaseWarehouse wh, BaseSupplier sup, BaseProduct p, String qty, String price) {
        OrderSaveRequest request = new OrderSaveRequest();
        request.setSupplierId(sup.getId());
        request.setWarehouseId(wh.getId());
        OrderItemRequest line = new OrderItemRequest();
        line.setSkuId(p.getId());
        line.setQty(bd(qty));
        line.setPrice(bd(price));
        request.setItems(List.of(line));
        return orderService.create(request);
    }

    private PurOrderItem orderLine(Long orderId) {
        return orderItemMapper.selectOne(Wrappers.<PurOrderItem>lambdaQuery()
                .eq(PurOrderItem::getDocId, orderId));
    }

    @Test
    void createInboundCarriesUnfulfilledLines() {
        BaseWarehouse wh = dataFactory.warehouse("OI-W1");
        BaseSupplier sup = dataFactory.supplier("OI-S1");
        BaseProduct p = dataFactory.product("OI-P1", MgmtMode.NONE);
        Long orderId = order(wh, sup, p, "10", "5");
        orderService.audit(orderId);

        Long inboundId = orderService.createInbound(orderId);

        PurInbound inbound = inboundMapper.selectById(inboundId);
        assertThat(inbound.getSourceOrderId()).isEqualTo(orderId);
        assertThat(inbound.getWarehouseId()).isEqualTo(wh.getId());
        PurInboundItem item = inboundItemMapper.selectOne(Wrappers.<PurInboundItem>lambdaQuery()
                .eq(PurInboundItem::getDocId, inboundId));
        assertThat(item.getQty()).isEqualByComparingTo(bd("10"));
        assertThat(item.getPrice()).isEqualByComparingTo(bd("5"));
        assertThat(item.getSourceOrderItemId()).isEqualTo(orderLine(orderId).getId());
    }

    @Test
    void overReceiveBoundaryMatrix() {
        // ratio=0.1：done ≤ qty×1.1 放行；超比例拒绝（40001）
        configService.update(ConfigService.KEY_OVER_RECEIVE_RATIO, "0.1");
        BaseWarehouse wh = dataFactory.warehouse("OI-W2");
        BaseSupplier sup = dataFactory.supplier("OI-S2");
        BaseProduct p = dataFactory.product("OI-P2", MgmtMode.NONE);
        Long orderId = order(wh, sup, p, "10", "5");
        orderService.audit(orderId);

        // = 上限 11：放行
        Long in1 = inboundService.create(draftInbound(orderId, wh, sup, p, "11", "5", orderLine(orderId).getId()));
        inboundService.audit(in1);
        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(bd("11"));
        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(30);

        // 再入 0.01 → 11.01 > 11 拒绝
        Long in2 = inboundService.create(draftInbound(orderId, wh, sup, p, "0.01", "5", orderLine(orderId).getId()));
        assertThatThrownBy(() -> inboundService.audit(in2))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.OVER_RECEIVE_EXCEEDED);
        asserter.assertConsistent();
    }

    @Test
    void partialInboundDoneQtyFlowAndAutoComplete() {
        BaseWarehouse wh = dataFactory.warehouse("OI-W3");
        BaseSupplier sup = dataFactory.supplier("OI-S3");
        BaseProduct p = dataFactory.product("OI-P3", MgmtMode.NONE);
        Long orderId = order(wh, sup, p, "10", "5");
        orderService.audit(orderId);

        Long in1 = inboundService.create(draftInbound(orderId, wh, sup, p, "4", "5", orderLine(orderId).getId()));
        inboundService.audit(in1);
        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(bd("4"));
        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(10);
        assertThat(orderMapper.selectById(orderId).getStatus().getCode()).isEqualTo(20);

        Long in2 = inboundService.create(draftInbound(orderId, wh, sup, p, "6", "5", orderLine(orderId).getId()));
        inboundService.audit(in2);
        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(bd("10"));
        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(30);
        // 全行终态 → 头 COMPLETED
        assertThat(orderMapper.selectById(orderId).getStatus().getCode()).isEqualTo(30);
        asserter.assertConsistent();
    }

    @Test
    void inboundWarehouseMustMatchOrder() {
        BaseWarehouse wh = dataFactory.warehouse("OI-W4");
        BaseWarehouse other = dataFactory.warehouse("OI-W5");
        BaseSupplier sup = dataFactory.supplier("OI-S4");
        BaseProduct p = dataFactory.product("OI-P4", MgmtMode.NONE);
        Long orderId = order(wh, sup, p, "10", "5");
        orderService.audit(orderId);

        assertThatThrownBy(() -> inboundService.create(draftInbound(orderId, other, sup, p, "5", "5", orderLine(orderId).getId())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仓库");
    }

    @Test
    void voidInboundRollsBackDoneQtyAndStock() {
        BaseWarehouse wh = dataFactory.warehouse("OI-W6");
        BaseSupplier sup = dataFactory.supplier("OI-S6");
        BaseProduct p = dataFactory.product("OI-P6", MgmtMode.NONE);
        Long orderId = order(wh, sup, p, "10", "5");
        orderService.audit(orderId);
        Long in1 = inboundService.create(draftInbound(orderId, wh, sup, p, "6", "5", orderLine(orderId).getId()));
        inboundService.audit(in1);
        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(bd("6"));

        inboundService.voidInbound(in1, "供应商发错货");

        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(10);
        assertThat(inboundMapper.selectById(in1).getStatus().getCode()).isEqualTo(50);
        asserter.assertConsistent();
    }

    @Test
    void voidOrderWithOpenInboundRejected() {
        BaseWarehouse wh = dataFactory.warehouse("OI-W7");
        BaseSupplier sup = dataFactory.supplier("OI-S7");
        BaseProduct p = dataFactory.product("OI-P7", MgmtMode.NONE);
        Long orderId = order(wh, sup, p, "10", "5");
        orderService.audit(orderId);
        Long in1 = inboundService.create(draftInbound(orderId, wh, sup, p, "5", "5", null));
        inboundService.audit(in1);

        assertThatThrownBy(() -> orderService.voidOrder(orderId, "不想买了"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("入库");
    }

    @Test
    void closeItemAndHeaderCompletion() {
        BaseWarehouse wh = dataFactory.warehouse("OI-W8");
        BaseSupplier sup = dataFactory.supplier("OI-S8");
        BaseProduct p = dataFactory.product("OI-P8", MgmtMode.NONE);
        Long orderId = order(wh, sup, p, "10", "5");
        orderService.audit(orderId);

        orderService.closeItem(orderId, orderLine(orderId).getId());

        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(40);
        assertThat(orderMapper.selectById(orderId).getStatus().getCode()).isEqualTo(30);
    }

    @Test
    void inboundAuditMovesStockAndMaterializesLotSn() {
        BaseWarehouse wh = dataFactory.warehouse("OI-W9");
        BaseSupplier sup = dataFactory.supplier("OI-S9");
        BaseProduct p = dataFactory.product("OI-P9", MgmtMode.LOT_SN);
        Long orderId = order(wh, sup, p, "2", "30");
        orderService.audit(orderId);

        InboundSaveRequest request = draftInbound(
                orderId, wh, sup, p, "2", "30", orderLine(orderId).getId());
        request.getItems().get(0).setLotNo("OI-L9");
        request.getItems().get(0).setProductionDate(LocalDate.of(2026, 3, 1));
        request.getItems().get(0).setSnList(List.of("OI-SN1", "OI-SN2"));
        Long inboundId = inboundService.create(request);
        inboundService.audit(inboundId);

        InvStock stock = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, wh.getId()).eq(InvStock::getSkuId, p.getId()));
        assertThat(stock.getQty()).isEqualByComparingTo(bd("2"));
        assertThat(stock.getCostAmount()).isEqualByComparingTo(bd("60"));
        InvLot lot = lotMapper.selectOne(Wrappers.<InvLot>lambdaQuery()
                .eq(InvLot::getSkuId, p.getId()).eq(InvLot::getLotNo, "OI-L9"));
        assertThat(lot).isNotNull();
        assertThat(snMapper.selectCount(Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSkuId, p.getId()).eq(InvSn::getStatus, SnStatus.IN_STOCK))).isEqualTo(2);
        asserter.assertConsistent();
    }

    @Test
    void countLockBlocksAudit() {
        BaseWarehouse wh = dataFactory.warehouse("OI-W10");
        BaseSupplier sup = dataFactory.supplier("OI-S10");
        BaseProduct p = dataFactory.product("OI-P10", MgmtMode.NONE);
        Long orderId = order(wh, sup, p, "5", "5");
        orderService.audit(orderId);
        Long inboundId = inboundService.create(draftInbound(orderId, wh, sup, p, "5", "5", orderLine(orderId).getId()));

        // 手工造 COUNTING 盘点单覆盖该 SKU（ADR-0004 状态即锁）
        InvCount count = new InvCount();
        count.setDocNo("PD-LOCK-" + System.nanoTime());
        count.setStatus(DocStatus.COUNTING);
        count.setDocDate(LocalDate.now());
        count.setCurrency("CNY");
        count.setWarehouseId(wh.getId());
        count.setFinFlag(0);
        countMapper.insert(count);
        InvCountItem ci = new InvCountItem();
        ci.setDocId(count.getId());
        ci.setLineNo(1);
        ci.setSkuId(p.getId());
        ci.setSkuName(p.getName());
        countItemMapper.insert(ci);

        assertThatThrownBy(() -> inboundService.audit(inboundId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.COUNT_LOCK_CONFLICT);
    }

    @Test
    void sourcelessInboundMovesStockDirectly() {
        BaseWarehouse wh = dataFactory.warehouse("OI-W11");
        BaseSupplier sup = dataFactory.supplier("OI-S11");
        BaseProduct p = dataFactory.product("OI-P11", MgmtMode.NONE);

        InboundSaveRequest request = draftInbound(null, wh, sup, p, "7", "3", null);
        Long inboundId = inboundService.create(request);
        inboundService.audit(inboundId);

        InvStock stock = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, wh.getId()).eq(InvStock::getSkuId, p.getId()));
        assertThat(stock.getQty()).isEqualByComparingTo(bd("7"));
        asserter.assertConsistent();
    }

    // ---------- M4 审查修复批次（2026-11-21 终审） ----------

    @Test
    void voidInboundOverReceivedKeepsOrderCompleted() {
        // I2：溢收后部分作废、done 仍 ≥ qty → 行保持 30、头保持 COMPLETED
        configService.update(ConfigService.KEY_OVER_RECEIVE_RATIO, "0.1");
        BaseWarehouse wh = dataFactory.warehouse("OI-W12");
        BaseSupplier sup = dataFactory.supplier("OI-S12");
        BaseProduct p = dataFactory.product("OI-P12", MgmtMode.NONE);
        Long orderId = order(wh, sup, p, "10", "5");
        orderService.audit(orderId);
        Long in1 = inboundService.create(draftInbound(orderId, wh, sup, p, "10", "5", orderLine(orderId).getId()));
        inboundService.audit(in1);
        Long in2 = inboundService.create(draftInbound(orderId, wh, sup, p, "1", "5", orderLine(orderId).getId()));
        inboundService.audit(in2);
        assertThat(orderMapper.selectById(orderId).getStatus().getCode()).isEqualTo(30);

        inboundService.voidInbound(in2, "多收 1 件退回");

        assertThat(orderLine(orderId).getDoneQty()).isEqualByComparingTo(bd("10"));
        assertThat(orderLine(orderId).getLineStatus()).isEqualTo(30);
        assertThat(orderMapper.selectById(orderId).getStatus().getCode()).isEqualTo(30);
        asserter.assertConsistent();
    }

    @Test
    void auditForeignSourceLineRejected() {
        // I3：行 sourceOrderItemId 必须属于头 sourceOrderId——越界行审核硬拒
        BaseWarehouse wh = dataFactory.warehouse("OI-W14");
        BaseSupplier sup = dataFactory.supplier("OI-S14");
        BaseProduct p = dataFactory.product("OI-P14", MgmtMode.NONE);
        Long orderA = order(wh, sup, p, "10", "5");
        orderService.audit(orderA);
        Long orderB = order(wh, sup, p, "5", "5");
        orderService.audit(orderB);

        Long inboundId = inboundService.create(draftInbound(orderA, wh, sup, p, "3", "5", orderLine(orderA).getId()));
        PurInboundItem row = inboundItemMapper.selectOne(Wrappers.<PurInboundItem>lambdaQuery()
                .eq(PurInboundItem::getDocId, inboundId));
        row.setSourceOrderItemId(orderLine(orderB).getId());
        inboundItemMapper.updateById(row);

        assertThatThrownBy(() -> inboundService.audit(inboundId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("源订单行");
    }

    @Test
    void draftSourceOrderRejectedAtInboundCreate() {
        // I3：带源入库单的源订单必须已审核/已完成——草稿订单保存即拒
        BaseWarehouse wh = dataFactory.warehouse("OI-W15");
        BaseSupplier sup = dataFactory.supplier("OI-S15");
        BaseProduct p = dataFactory.product("OI-P15", MgmtMode.NONE);
        Long orderDraft = order(wh, sup, p, "10", "5");

        assertThatThrownBy(() -> inboundService.create(
                draftInbound(orderDraft, wh, sup, p, "3", "5", orderLine(orderDraft).getId())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审核");
    }

    @Test
    void voidDraftInboundAllowedWithoutStockEffect() {
        // I6：草稿入库单直接作废（此前只能"先审核动库存再作废"）
        BaseWarehouse wh = dataFactory.warehouse("OI-W16");
        BaseSupplier sup = dataFactory.supplier("OI-S16");
        BaseProduct p = dataFactory.product("OI-P16", MgmtMode.NONE);
        Long inboundId = inboundService.create(draftInbound(null, wh, sup, p, "7", "3", null));

        inboundService.voidInbound(inboundId, "建错了");

        assertThat(inboundMapper.selectById(inboundId).getStatus().getCode()).isEqualTo(50);
        assertThat(stockMapper.selectCount(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, wh.getId()).eq(InvStock::getSkuId, p.getId()))).isZero();
        asserter.assertConsistent();
    }

    @Test
    void snFractionQtyRejectedWithFriendlyError() {
        // M5：管SN商品数量非整数 → 10001 友好错误（此前 intValueExact 抛 ArithmeticException 500）
        BaseWarehouse wh = dataFactory.warehouse("OI-W17");
        BaseSupplier sup = dataFactory.supplier("OI-S17");
        BaseProduct p = dataFactory.product("OI-P17", MgmtMode.SN);
        InboundSaveRequest request = draftInbound(null, wh, sup, p, "1.5", "5", null);
        request.getItems().get(0).setSnList(List.of("OI-SNA"));
        Long inboundId = inboundService.create(request);

        assertThatThrownBy(() -> inboundService.audit(inboundId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("整数");
    }

    @Test
    void warehouseScopeFiltersPageAndBlocksOtherWarehouse() {
        // I1：仓库数据权限——列表过滤 + 跨仓操作拒绝（spec §5，对 M3 期初同模式）
        BaseWarehouse whA = dataFactory.warehouse("OI-W13A");
        BaseWarehouse whB = dataFactory.warehouse("OI-W13B");
        BaseSupplier sup = dataFactory.supplier("OI-S13");
        BaseProduct p = dataFactory.product("OI-P13", MgmtMode.NONE);
        Long inA = inboundService.create(draftInbound(null, whA, sup, p, "1", "1", null));
        Long inB = inboundService.create(draftInbound(null, whB, sup, p, "1", "1", null));

        dataFactory.roleWithPerms("R_OISCOPE", "入库范围", "purchase:inbound:list");
        var u = dataFactory.user("oiscope01", "pwd123", "R_OISCOPE");
        dataFactory.bindWarehouses(u.getId(), whA.getId());
        StpUtil.logout();
        StpUtil.login(u.getId());

        var records = inboundService.page(1, 20, null).getRecords();
        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(r -> assertThat(r.getWarehouseId()).isEqualTo(whA.getId()));
        assertThat(records).noneSatisfy(r -> assertThat(r.getId()).isEqualTo(inB));
        assertThatThrownBy(() -> inboundService.audit(inB))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NO_PERMISSION);
        // 零仓库用户查空而非放行
        var u0 = dataFactory.user("oiscope02", "pwd123", "R_OISCOPE");
        StpUtil.logout();
        StpUtil.login(u0.getId());
        assertThat(inboundService.page(1, 20, null).getRecords()).isEmpty();
    }

    @Test
    void auditedOptionsOnlyExposeAuditedDocs() {
        // 源单下拉内容：订单/入库单的 options 只含 AUDITED，草稿/作废不出现
        BaseWarehouse wh = dataFactory.warehouse("OI-W18");
        BaseSupplier sup = dataFactory.supplier("OI-S18");
        BaseProduct p = dataFactory.product("OI-P18", MgmtMode.NONE);
        Long orderDraft = order(wh, sup, p, "5", "5");
        Long orderAudited = order(wh, sup, p, "5", "5");
        orderService.audit(orderAudited);

        var orderOpts = orderService.auditedOptions();
        assertThat(orderOpts).extracting(DocOptionVO::id)
                .contains(orderAudited).doesNotContain(orderDraft);

        Long inDraft = inboundService.create(draftInbound(null, wh, sup, p, "1", "1", null));
        Long inAudited = inboundService.create(draftInbound(null, wh, sup, p, "2", "2", null));
        inboundService.audit(inAudited);

        var inOpts = inboundService.auditedOptions();
        assertThat(inOpts).extracting(DocOptionVO::id)
                .contains(inAudited).doesNotContain(inDraft);
        assertThat(inOpts.get(0).docNo()).isNotBlank();
        asserter.assertConsistent();
    }

    private InboundSaveRequest draftInbound(Long orderId, BaseWarehouse wh,
                                                                       BaseSupplier sup, BaseProduct p,
                                                                       String qty, String price, Long sourceItemId) {
        InboundSaveRequest request = new InboundSaveRequest();
        request.setSupplierId(sup.getId());
        request.setWarehouseId(wh.getId());
        request.setSourceOrderId(orderId);
        InboundItemRequest line = new InboundItemRequest();
        line.setSkuId(p.getId());
        line.setQty(bd(qty));
        line.setPrice(bd(price));
        line.setSourceOrderItemId(sourceItemId);
        request.setItems(List.of(line));
        return request;
    }
}
