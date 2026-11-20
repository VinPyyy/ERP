package com.wille.erp.purchase.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.entity.InvFlow;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvFlowMapper;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.purchase.dto.InboundItemRequest;
import com.wille.erp.purchase.dto.InboundSaveRequest;
import com.wille.erp.purchase.dto.OrderItemRequest;
import com.wille.erp.purchase.dto.OrderSaveRequest;
import com.wille.erp.purchase.dto.ReturnItemRequest;
import com.wille.erp.purchase.dto.ReturnSaveRequest;
import com.wille.erp.purchase.entity.PurInboundItem;
import com.wille.erp.purchase.mapper.PurInboundItemMapper;
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
}
