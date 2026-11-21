package com.wille.erp.sale;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseCustomer;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.purchase.dto.OrderItemRequest;
import com.wille.erp.purchase.dto.OrderSaveRequest;
import com.wille.erp.purchase.service.InboundService;
import com.wille.erp.sale.dto.OutboundItemRequest;
import com.wille.erp.sale.dto.OutboundSaveRequest;
import com.wille.erp.sale.dto.QuoteItemRequest;
import com.wille.erp.sale.dto.QuoteSaveRequest;
import com.wille.erp.sale.dto.ReturnItemRequest;
import com.wille.erp.sale.dto.ReturnSaveRequest;
import com.wille.erp.sale.dto.SaleOrderItemRequest;
import com.wille.erp.sale.entity.SalOrderItem;
import com.wille.erp.sale.entity.SalOutboundItem;
import com.wille.erp.sale.mapper.SalOrderItemMapper;
import com.wille.erp.sale.mapper.SalOutboundItemMapper;
import com.wille.erp.sale.service.QuoteService;
import com.wille.erp.sale.service.SaleOrderService;
import com.wille.erp.sale.service.SaleOutboundService;
import com.wille.erp.sale.service.SaleReturnService;
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

/**
 * 一期验收标准自动化（M5 任务 6，spec §6/§7）：采购入库 → 报价 → 销售订单锁定 →
 * 分批出库核销 → 完结 → 退货回库 → 全链逆序作废 → 库存/锁定/流水四层归零且对账零差异。
 * 每个节点断言锁定不变量（locked_qty = SUM(remain)）与 StockInvariantAsserter 四层。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SaleEndToEndTest {

    @Autowired
    private com.wille.erp.purchase.service.OrderService purchaseOrderService;
    @Autowired
    private InboundService purchaseInboundService;
    @Autowired
    private QuoteService quoteService;
    @Autowired
    private SaleOrderService saleOrderService;
    @Autowired
    private SaleOutboundService outboundService;
    @Autowired
    private SaleReturnService returnService;
    @Autowired
    private SalOrderItemMapper saleOrderItemMapper;
    @Autowired
    private SalOutboundItemMapper outboundItemMapper;
    @Autowired
    private InvStockMapper stockMapper;
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

    private InvStock stock(Long whId, Long skuId) {
        return stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, whId).eq(InvStock::getSkuId, skuId));
    }

    @Test
    void fullChainPurchaseToSaleReturnAndReverseVoid() {
        BaseWarehouse wh = dataFactory.warehouse("E2E-W");
        BaseSupplier sup = dataFactory.supplier("E2E-S");
        BaseCustomer cu = dataFactory.customer("E2E-C");
        BaseProduct pn = dataFactory.product("E2E-PN", MgmtMode.NONE);
        BaseProduct ps = dataFactory.product("E2E-PS", MgmtMode.SN);

        // 1 采购链入库：NONE 20@5、SN 2@30
        OrderSaveRequest po = new OrderSaveRequest();
        po.setSupplierId(sup.getId());
        po.setWarehouseId(wh.getId());
        OrderItemRequest pol = new OrderItemRequest();
        pol.setSkuId(pn.getId());
        pol.setQty(bd("20"));
        pol.setPrice(bd("5"));
        po.setItems(List.of(pol));
        Long poId = purchaseOrderService.create(po);
        purchaseOrderService.audit(poId);
        Long piId = purchaseOrderService.createInbound(poId);
        purchaseInboundService.audit(piId);
        assertThat(stock(wh.getId(), pn.getId()).getQty()).isEqualByComparingTo(bd("20"));
        asserter.assertConsistent();

        // 2 报价 → 转销售订单 → 审核锁定
        QuoteSaveRequest quote = new QuoteSaveRequest();
        quote.setCustomerId(cu.getId());
        quote.setValidUntil(LocalDate.now().plusDays(15));
        QuoteItemRequest ql = new QuoteItemRequest();
        ql.setSkuId(pn.getId());
        ql.setQty(bd("10"));
        ql.setPrice(bd("9"));
        quote.setItems(List.of(ql));
        Long quoteId = quoteService.create(quote);
        quoteService.audit(quoteId);
        Long soId = quoteService.convertToOrder(quoteId, wh.getId());
        saleOrderService.audit(soId);
        assertThat(stock(wh.getId(), pn.getId()).getLockedQty()).isEqualByComparingTo(bd("10"));
        asserter.assertConsistent();

        // 3 分批出库 6 + 4 → 完结
        Long ob1 = outboundService.create(sourcedOutbound(cu, wh, pn, soId, "6"));
        outboundService.audit(ob1);
        assertThat(stock(wh.getId(), pn.getId()).getQty()).isEqualByComparingTo(bd("14"));
        assertThat(stock(wh.getId(), pn.getId()).getLockedQty()).isEqualByComparingTo(bd("4"));
        asserter.assertConsistent();

        Long ob2 = outboundService.create(sourcedOutbound(cu, wh, pn, soId, "4"));
        outboundService.audit(ob2);
        assertThat(saleOrderItem(soId).getDoneQty()).isEqualByComparingTo(bd("10"));
        assertThat(saleOrderItem(soId).getLineStatus()).isEqualTo(30);
        assertThat(stock(wh.getId(), pn.getId()).getLockedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        asserter.assertConsistent();

        // 4 有源退货 2（价强制出库价 9）
        Long ret = returnService.create(sourcedReturn(cu, wh, pn, ob2, "2", null));
        returnService.audit(ret);
        assertThat(stock(wh.getId(), pn.getId()).getQty()).isEqualByComparingTo(bd("12"));
        asserter.assertConsistent();

        // 5 SN 支线：采购入库 2 SN → 直接出库核销 → 退货 1 回库
        stockServiceInboundSn(wh, ps);
        Long snOb = outboundService.create(snOutbound(cu, wh, ps));
        outboundService.audit(snOb);
        Long snRet = returnService.create(sourcedReturn(cu, wh, ps, snOb, "1", List.of("E2E-SN-A")));
        returnService.audit(snRet);
        InvSn a = snMapper.selectOne(Wrappers.<InvSn>lambdaQuery().eq(InvSn::getSnCode, "E2E-SN-A"));
        assertThat(a.getStatus()).isEqualTo(SnStatus.IN_STOCK);
        InvSn b = snMapper.selectOne(Wrappers.<InvSn>lambdaQuery().eq(InvSn::getSnCode, "E2E-SN-B"));
        assertThat(b.getStatus()).isEqualTo(SnStatus.OUT);
        asserter.assertConsistent();

        // 6 全链逆序作废：退货→出库→销售订单→采购入库
        returnService.voidReturn(snRet, "E2E 收尾");
        returnService.voidReturn(ret, "E2E 收尾");
        outboundService.voidOutbound(snOb, "E2E 收尾");
        outboundService.voidOutbound(ob2, "E2E 收尾");
        outboundService.voidOutbound(ob1, "E2E 收尾");
        assertThat(stock(wh.getId(), pn.getId()).getLockedQty()).isEqualByComparingTo(bd("10"));
        saleOrderService.voidOrder(soId, "E2E 收尾");
        purchaseInboundService.voidInbound(piId, "E2E 收尾");

        assertThat(stock(wh.getId(), pn.getId()).getQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stock(wh.getId(), pn.getId()).getLockedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        // SN 支线经出库作废回库 2 件（facade 注入无采购单可冲，终态即在库 2）
        assertThat(stock(wh.getId(), ps.getId()).getQty()).isEqualByComparingTo(bd("2"));
        assertThat(snMapper.selectList(Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSkuId, ps.getId()).eq(InvSn::getStatus, SnStatus.IN_STOCK))).hasSize(2);
        asserter.assertConsistent();
    }

    private SalOrderItem saleOrderItem(Long soId) {
        return saleOrderItemMapper.selectList(Wrappers.<SalOrderItem>lambdaQuery()
                .eq(SalOrderItem::getDocId, soId)).get(0);
    }

    private OutboundSaveRequest sourcedOutbound(BaseCustomer cu, BaseWarehouse wh, BaseProduct p,
                                                Long soId, String qty) {
        OutboundSaveRequest req = new OutboundSaveRequest();
        req.setCustomerId(cu.getId());
        req.setWarehouseId(wh.getId());
        req.setSourceOrderId(soId);
        OutboundItemRequest line = new OutboundItemRequest();
        line.setSourceOrderItemId(saleOrderItem(soId).getId());
        line.setSkuId(p.getId());
        line.setQty(bd(qty));
        line.setPrice(bd("9"));
        req.setItems(List.of(line));
        return req;
    }

    private OutboundSaveRequest snOutbound(BaseCustomer cu, BaseWarehouse wh, BaseProduct p) {
        OutboundSaveRequest req = new OutboundSaveRequest();
        req.setCustomerId(cu.getId());
        req.setWarehouseId(wh.getId());
        req.setCarrier("顺丰");
        req.setTrackingNo("SF-E2E-1");
        OutboundItemRequest line = new OutboundItemRequest();
        line.setSkuId(p.getId());
        line.setQty(bd("2"));
        line.setPrice(bd("40"));
        line.setSnList(List.of("E2E-SN-A", "E2E-SN-B"));
        req.setItems(List.of(line));
        return req;
    }

    private ReturnSaveRequest sourcedReturn(BaseCustomer cu, BaseWarehouse wh, BaseProduct p,
                                           Long outboundId, String qty, List<String> sns) {
        ReturnSaveRequest req = new ReturnSaveRequest();
        req.setCustomerId(cu.getId());
        req.setWarehouseId(wh.getId());
        req.setSourceOutboundId(outboundId);
        ReturnItemRequest line = new ReturnItemRequest();
        SalOutboundItem obItem = outboundItemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, outboundId)).get(0);
        line.setSourceOutboundItemId(obItem.getId());
        line.setSkuId(p.getId());
        line.setQty(bd(qty));
        line.setSnList(sns);
        line.setReason("E2E 退货");
        req.setItems(List.of(line));
        return req;
    }

    private void stockServiceInboundSn(BaseWarehouse wh, BaseProduct p) {
        var ref = new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 900L, "E2E-SNIN");
        var line = new MoveLine(p.getId(), bd("2"), bd("30"), null, List.of("E2E-SN-A", "E2E-SN-B"), 900L);
        stockFacade.inbound(new MoveCmd(wh.getId(), ref, List.of(line)));
    }

    @Autowired
    private StockService stockFacade;
}
