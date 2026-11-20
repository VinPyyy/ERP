package com.wille.erp.purchase.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.entity.InvCount;
import com.wille.erp.inventory.entity.InvCountItem;
import com.wille.erp.inventory.entity.InvFlow;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvCountItemMapper;
import com.wille.erp.inventory.mapper.InvCountMapper;
import com.wille.erp.inventory.mapper.InvFlowMapper;
import com.wille.erp.inventory.mapper.InvLotMapper;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.purchase.dto.InboundItemRequest;
import com.wille.erp.purchase.dto.InboundSaveRequest;
import com.wille.erp.purchase.dto.OrderItemRequest;
import com.wille.erp.purchase.dto.OrderSaveRequest;
import com.wille.erp.purchase.dto.ReturnItemRequest;
import com.wille.erp.purchase.dto.ReturnSaveRequest;
import com.wille.erp.purchase.entity.PurInboundItem;
import com.wille.erp.purchase.entity.PurReturn;
import com.wille.erp.purchase.entity.PurReturnItem;
import com.wille.erp.purchase.mapper.PurInboundItemMapper;
import com.wille.erp.purchase.mapper.PurReturnItemMapper;
import com.wille.erp.purchase.mapper.PurReturnMapper;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采购退货（M4 任务 4，spec §2.4/§8-6 对称裁决）：有原单价不可改+累计≤原量；
 * 无原单手录价；成本按当前均价；SN 核销出库；作废回冲（出库方向）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReturnServiceTest {

    @Autowired
    private ReturnService returnService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private InboundService inboundService;
    @Autowired
    private PurInboundItemMapper inboundItemMapper;
    @Autowired
    private InvStockMapper stockMapper;
    @Autowired
    private InvFlowMapper flowMapper;
    @Autowired
    private InvSnMapper snMapper;
    @Autowired
    private StockInvariantAsserter asserter;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private PurReturnMapper returnMapper;
    @Autowired
    private PurReturnItemMapper returnItemMapper;
    @Autowired
    private InvCountMapper countMapper;
    @Autowired
    private InvCountItemMapper countItemMapper;
    @Autowired
    private InvLotMapper lotMapper;

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

    private record Fixture(BaseWarehouse wh, BaseSupplier sup, BaseProduct p, Long inboundId, Long inboundItemId) {
    }

    private Fixture fixture(String tag, MgmtMode mode) {
        BaseWarehouse wh = dataFactory.warehouse("RT-" + tag);
        BaseSupplier sup = dataFactory.supplier("RTS-" + tag);
        BaseProduct p = dataFactory.product("RTP-" + tag, mode);
        OrderSaveRequest order = new OrderSaveRequest();
        order.setSupplierId(sup.getId());
        order.setWarehouseId(wh.getId());
        OrderItemRequest line = new OrderItemRequest();
        line.setSkuId(p.getId());
        line.setQty(bd("10"));
        line.setPrice(bd("5"));
        order.setItems(List.of(line));
        Long orderId = orderService.create(order);
        orderService.audit(orderId);
        InboundSaveRequest inbound = new InboundSaveRequest();
        inbound.setSupplierId(sup.getId());
        inbound.setWarehouseId(wh.getId());
        inbound.setSourceOrderId(orderId);
        InboundItemRequest il = new InboundItemRequest();
        il.setSkuId(p.getId());
        il.setQty(bd("10"));
        il.setPrice(bd("5"));
        inbound.setItems(List.of(il));
        Long inboundId = inboundService.create(inbound);
        inboundService.audit(inboundId);
        PurInboundItem item = inboundItemMapper.selectOne(Wrappers.<PurInboundItem>lambdaQuery()
                .eq(PurInboundItem::getDocId, inboundId));
        return new Fixture(wh, sup, p, inboundId, item.getId());
    }

    private ReturnSaveRequest returnReq(Fixture f, String qty, BigDecimal price, String reason) {
        ReturnSaveRequest request = new ReturnSaveRequest();
        request.setSupplierId(f.sup().getId());
        request.setWarehouseId(f.wh().getId());
        request.setSourceInboundId(f.inboundId());
        ReturnItemRequest item = new ReturnItemRequest();
        item.setSourceInboundItemId(f.inboundItemId());
        item.setSkuId(f.p().getId());
        item.setQty(bd(qty));
        item.setPrice(price);
        item.setReason(reason);
        request.setItems(List.of(item));
        return request;
    }

    @Test
    void returnWithSourceCarriesPriceAndBlocksOverReturn() {
        Fixture f = fixture("SRC", MgmtMode.NONE);

        Long r1 = returnService.create(returnReq(f, "4", null, "部分退货"));
        returnService.audit(r1);

        // 有原单：价强制原单价（手传 99 被忽略）
        Long r2 = returnService.create(returnReq(f, "7", bd("99"), "超退"));
        assertThatThrownBy(() -> returnService.audit(r2))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.RETURN_EXCEED_SOURCE);
        asserter.assertConsistent();
    }

    @Test
    void returnWithoutSourceAllowsManualPrice() {
        BaseWarehouse wh = dataFactory.warehouse("RT-NOSRC");
        BaseSupplier sup = dataFactory.supplier("RTS-NOSRC");
        BaseProduct p = dataFactory.product("RTP-NOSRC", MgmtMode.NONE);
        // 先无源入库 5@8，再无源退货 2@9（手录价，成本按均价 8 结转）
        InboundSaveRequest inbound = new InboundSaveRequest();
        inbound.setSupplierId(sup.getId());
        inbound.setWarehouseId(wh.getId());
        InboundItemRequest il = new InboundItemRequest();
        il.setSkuId(p.getId());
        il.setQty(bd("5"));
        il.setPrice(bd("8"));
        inbound.setItems(List.of(il));
        Long inboundId = inboundService.create(inbound);
        inboundService.audit(inboundId);

        ReturnSaveRequest request = new ReturnSaveRequest();
        request.setSupplierId(sup.getId());
        request.setWarehouseId(wh.getId());
        ReturnItemRequest item = new ReturnItemRequest();
        item.setSkuId(p.getId());
        item.setQty(bd("2"));
        item.setPrice(bd("9"));
        item.setReason("无原单退货");
        request.setItems(List.of(item));
        Long rid = returnService.create(request);
        returnService.audit(rid);

        InvFlow flow = flowMapper.selectList(Wrappers.<InvFlow>lambdaQuery()
                        .eq(InvFlow::getBizType, BizType.PUR_RETURN_OUT)
                        .eq(InvFlow::getBizDocId, rid)).get(0);
        assertThat(flow.getPrice()).isEqualByComparingTo(bd("9"));       // 业务价=手录
        assertThat(flow.getCostPrice()).isEqualByComparingTo(bd("8"));   // 计价=当前均价
        asserter.assertConsistent();
    }

    @Test
    void returnAuditOutsStockAndSn() {
        // fixture 仅提供仓库/供应商（NONE 商品）；SN 链路单独造
        Fixture f = fixture("SN", MgmtMode.NONE);
        BaseWarehouse wh = f.wh();
        BaseSupplier sup = f.sup();
        BaseProduct snProduct = dataFactory.product("RTP-SN2", MgmtMode.SN);
        InboundSaveRequest inbound = new InboundSaveRequest();
        inbound.setSupplierId(sup.getId());
        inbound.setWarehouseId(wh.getId());
        InboundItemRequest il = new InboundItemRequest();
        il.setSkuId(snProduct.getId());
        il.setQty(bd("2"));
        il.setPrice(bd("9"));
        il.setSnList(List.of("RT-SN-A", "RT-SN-B"));
        inbound.setItems(List.of(il));
        Long inboundId = inboundService.create(inbound);
        inboundService.audit(inboundId);
        PurInboundItem item = inboundItemMapper.selectOne(Wrappers.<PurInboundItem>lambdaQuery()
                .eq(PurInboundItem::getDocId, inboundId));

        ReturnSaveRequest request = new ReturnSaveRequest();
        request.setSupplierId(sup.getId());
        request.setWarehouseId(wh.getId());
        request.setSourceInboundId(inboundId);
        ReturnItemRequest ri = new ReturnItemRequest();
        ri.setSourceInboundItemId(item.getId());
        ri.setSkuId(snProduct.getId());
        ri.setQty(bd("1"));
        ri.setSnList(List.of("RT-SN-A"));
        ri.setReason("SN 退货");
        request.setItems(List.of(ri));
        Long rid = returnService.create(request);
        returnService.audit(rid);

        InvSn sn = snMapper.selectOne(Wrappers.<InvSn>lambdaQuery().eq(InvSn::getSnCode, "RT-SN-A"));
        assertThat(sn.getStatus()).isEqualTo(SnStatus.OUT);
        assertThat(sn.getOutDocId()).isEqualTo(rid);
        InvStock stock = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, wh.getId()).eq(InvStock::getSkuId, snProduct.getId()));
        assertThat(stock.getQty()).isEqualByComparingTo(bd("1"));
        asserter.assertConsistent();
    }

    @Test
    void voidReturnReversesOutboundDirection() {
        Fixture f = fixture("VOID", MgmtMode.NONE);
        Long rid = returnService.create(returnReq(f, "4", null, "退错"));
        returnService.audit(rid);
        InvStock before = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, f.wh().getId()).eq(InvStock::getSkuId, f.p().getId()));
        assertThat(before.getQty()).isEqualByComparingTo(bd("6"));

        returnService.voidReturn(rid, "误操作");

        InvStock after = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, f.wh().getId()).eq(InvStock::getSkuId, f.p().getId()));
        assertThat(after.getQty()).isEqualByComparingTo(bd("10"));
        asserter.assertConsistent();
    }

    @Test
    void voidInboundWithOpenReturnRejected() {
        Fixture f = fixture("BLK", MgmtMode.NONE);
        Long rid = returnService.create(returnReq(f, "4", null, "退货"));
        returnService.audit(rid);

        assertThatThrownBy(() -> inboundService.voidInbound(f.inboundId(), "尝试作废入库"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.VOID_BLOCKED_BY_DOWNSTREAM);
    }

    // ---------- M4 审查修复批次（2026-11-21 终审） ----------

    private Fixture lotFixture(String tag, String lotNo) {
        BaseWarehouse wh = dataFactory.warehouse("RTL-W" + tag);
        BaseSupplier sup = dataFactory.supplier("RTL-S" + tag);
        BaseProduct p = dataFactory.product("RTL-P" + tag, MgmtMode.LOT);
        OrderSaveRequest order = new OrderSaveRequest();
        order.setSupplierId(sup.getId());
        order.setWarehouseId(wh.getId());
        OrderItemRequest line = new OrderItemRequest();
        line.setSkuId(p.getId());
        line.setQty(bd("10"));
        line.setPrice(bd("5"));
        order.setItems(List.of(line));
        Long orderId = orderService.create(order);
        orderService.audit(orderId);
        InboundSaveRequest inbound = new InboundSaveRequest();
        inbound.setSupplierId(sup.getId());
        inbound.setWarehouseId(wh.getId());
        inbound.setSourceOrderId(orderId);
        InboundItemRequest il = new InboundItemRequest();
        il.setSkuId(p.getId());
        il.setQty(bd("10"));
        il.setPrice(bd("5"));
        il.setLotNo(lotNo);
        inbound.setItems(List.of(il));
        Long inboundId = inboundService.create(inbound);
        inboundService.audit(inboundId);
        PurInboundItem item = inboundItemMapper.selectOne(Wrappers.<PurInboundItem>lambdaQuery()
                .eq(PurInboundItem::getDocId, inboundId));
        return new Fixture(wh, sup, p, inboundId, item.getId());
    }

    @Test
    void draftReturnDoesNotHoldQuota() {
        // I4：累计配额只计已审核退货——废弃草稿不永久占额
        Fixture f = fixture("QUOTA", MgmtMode.NONE);
        Long r1 = returnService.create(returnReq(f, "4", null, "破损"));
        returnService.audit(r1);
        returnService.create(returnReq(f, "7", null, "误购草稿")); // 留在 DRAFT，不计配额
        Long r3 = returnService.create(returnReq(f, "6", null, "质量"));

        returnService.audit(r3); // 4+6 ≤ 10 放行（旧口径 4+7+6>10 会误拒）

        InvStock stock = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, f.wh().getId()).eq(InvStock::getSkuId, f.p().getId()));
        assertThat(stock.getQty()).isEqualByComparingTo(bd("0"));
        asserter.assertConsistent();
    }

    @Test
    void returnExactlySourceQtyAllowed() {
        // I9 三档补齐：= 原入库量放行
        Fixture f = fixture("EDGE", MgmtMode.NONE);
        Long rid = returnService.create(returnReq(f, "10", null, "整单退"));
        returnService.audit(rid);
        InvStock stock = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, f.wh().getId()).eq(InvStock::getSkuId, f.p().getId()));
        assertThat(stock.getQty()).isEqualByComparingTo(bd("0"));
        asserter.assertConsistent();
    }

    @Test
    void voidDraftReturnAllowed() {
        // I6：草稿退货单直接作废，不动库存
        Fixture f = fixture("VDR", MgmtMode.NONE);
        Long rid = returnService.create(returnReq(f, "4", null, "建错了"));

        returnService.voidReturn(rid, "行录错，未审核");

        assertThat(returnMapper.selectById(rid).getStatus().getCode()).isEqualTo(50);
        InvStock stock = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, f.wh().getId()).eq(InvStock::getSkuId, f.p().getId()));
        assertThat(stock.getQty()).isEqualByComparingTo(bd("10"));
        asserter.assertConsistent();
    }

    @Test
    void returnAgainstDraftInboundRejected() {
        // I3：退货的源入库单必须已审核
        Fixture f = fixture("DSRC", MgmtMode.NONE);
        InboundSaveRequest draftIn = new InboundSaveRequest();
        draftIn.setSupplierId(f.sup().getId());
        draftIn.setWarehouseId(f.wh().getId());
        InboundItemRequest il = new InboundItemRequest();
        il.setSkuId(f.p().getId());
        il.setQty(bd("5"));
        il.setPrice(bd("5"));
        draftIn.setItems(List.of(il));
        Long draftId = inboundService.create(draftIn);
        PurInboundItem draftItem = inboundItemMapper.selectOne(Wrappers.<PurInboundItem>lambdaQuery()
                .eq(PurInboundItem::getDocId, draftId));

        ReturnSaveRequest request = new ReturnSaveRequest();
        request.setSupplierId(f.sup().getId());
        request.setWarehouseId(f.wh().getId());
        request.setSourceInboundId(draftId);
        ReturnItemRequest ri = new ReturnItemRequest();
        ri.setSourceInboundItemId(draftItem.getId());
        ri.setSkuId(f.p().getId());
        ri.setQty(bd("1"));
        ri.setReason("非法源单");
        request.setItems(List.of(ri));

        assertThatThrownBy(() -> returnService.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审核");
    }

    @Test
    void countLockBlocksReturnAudit() {
        // I9：盘点锁同样拦退货审核（计划审查重点 5 的退货侧补位）
        Fixture f = fixture("LOCK", MgmtMode.NONE);
        Long rid = returnService.create(returnReq(f, "4", null, "退货"));
        InvCount count = new InvCount();
        count.setDocNo("PD-RT-LOCK-" + System.nanoTime());
        count.setStatus(DocStatus.COUNTING);
        count.setDocDate(LocalDate.now());
        count.setCurrency("CNY");
        count.setWarehouseId(f.wh().getId());
        count.setFinFlag(0);
        countMapper.insert(count);
        InvCountItem ci = new InvCountItem();
        ci.setDocId(count.getId());
        ci.setLineNo(1);
        ci.setSkuId(f.p().getId());
        ci.setSkuName(f.p().getName());
        countItemMapper.insert(ci);

        assertThatThrownBy(() -> returnService.audit(rid))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.COUNT_LOCK_CONFLICT);
        asserter.assertConsistent();
    }

    @Test
    void noSourceLotReturnResolvesExistingLot() {
        // I5：无原单管批次退货——按批号定位既有批次，出库成功
        Fixture f = lotFixture("L1", "RTL-LOT-1");
        ReturnSaveRequest request = new ReturnSaveRequest();
        request.setSupplierId(f.sup().getId());
        request.setWarehouseId(f.wh().getId());
        ReturnItemRequest ri = new ReturnItemRequest();
        ri.setSkuId(f.p().getId());
        ri.setQty(bd("4"));
        ri.setPrice(bd("5"));
        ri.setLotNo("RTL-LOT-1");
        ri.setReason("批次退货");
        request.setItems(List.of(ri));
        Long rid = returnService.create(request);

        returnService.audit(rid);

        InvLot lot = lotMapper.selectOne(
                Wrappers.<InvLot>lambdaQuery()
                        .eq(InvLot::getSkuId, f.p().getId())
                        .eq(InvLot::getLotNo, "RTL-LOT-1"));
        PurReturnItem row = returnItemMapper.selectList(Wrappers.<PurReturnItem>lambdaQuery()
                .eq(PurReturnItem::getDocId, rid)).get(0);
        assertThat(row.getLotId()).isEqualTo(lot.getId());
        InvStock stock = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, f.wh().getId()).eq(InvStock::getSkuId, f.p().getId()));
        assertThat(stock.getQty()).isEqualByComparingTo(bd("6"));
        asserter.assertConsistent();
    }

    @Test
    void noSourceLotReturnUnknownLotRejected() {
        // I5：出库不允许凭空造批次
        Fixture f = lotFixture("L2", "RTL-LOT-2");
        ReturnSaveRequest request = new ReturnSaveRequest();
        request.setSupplierId(f.sup().getId());
        request.setWarehouseId(f.wh().getId());
        ReturnItemRequest ri = new ReturnItemRequest();
        ri.setSkuId(f.p().getId());
        ri.setQty(bd("2"));
        ri.setPrice(bd("5"));
        ri.setLotNo("RTL-NOPE");
        ri.setReason("不存在批次");
        request.setItems(List.of(ri));
        Long rid = returnService.create(request);

        assertThatThrownBy(() -> returnService.audit(rid))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("批次不存在");
    }

    @Test
    void sourcedReturnOverridesMismatchedLotNo() {
        // I5：有原单行批号以源行为准（用户传错批号被服务端覆盖）
        Fixture f = lotFixture("L3", "RTL-LOT-3");
        ReturnSaveRequest request = returnReq(f, "4", null, "批次带错");
        request.getItems().get(0).setLotNo("RTL-WRONG");
        Long rid = returnService.create(request);

        returnService.audit(rid);

        PurReturnItem row = returnItemMapper.selectList(Wrappers.<PurReturnItem>lambdaQuery()
                .eq(PurReturnItem::getDocId, rid)).get(0);
        assertThat(row.getLotNo()).isEqualTo("RTL-LOT-3");
        InvStock stock = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, f.wh().getId()).eq(InvStock::getSkuId, f.p().getId()));
        assertThat(stock.getQty()).isEqualByComparingTo(bd("6"));
        asserter.assertConsistent();
    }

    @Test
    void createFromInboundCarriesRemainingQty() {
        // I7：spec §5 端点 create-return——按可退余量（扣非作废退货）生成草稿
        Fixture f = fixture("CFR", MgmtMode.NONE);
        Long r1 = returnService.create(returnReq(f, "4", null, "破损"));
        returnService.audit(r1);

        Long draftId = returnService.createFromInbound(f.inboundId());

        PurReturn doc = returnMapper.selectById(draftId);
        assertThat(doc.getSourceInboundId()).isEqualTo(f.inboundId());
        assertThat(doc.getWarehouseId()).isEqualTo(f.wh().getId());
        assertThat(doc.getStatus().getCode()).isEqualTo(10);
        PurReturnItem row = returnItemMapper.selectList(Wrappers.<PurReturnItem>lambdaQuery()
                .eq(PurReturnItem::getDocId, draftId)).get(0);
        assertThat(row.getSourceInboundItemId()).isEqualTo(f.inboundItemId());
        assertThat(row.getQty()).isEqualByComparingTo(bd("6"));
        assertThat(row.getPrice()).isEqualByComparingTo(bd("5"));
        assertThat(row.getReason()).isNotBlank();
        asserter.assertConsistent();
    }
}
