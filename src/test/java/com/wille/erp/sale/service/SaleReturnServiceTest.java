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
import com.wille.erp.inventory.api.LotApi;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.sale.dto.OutboundSaveRequest;
import com.wille.erp.sale.dto.OutboundItemRequest;
import com.wille.erp.sale.dto.ReturnItemRequest;
import com.wille.erp.sale.dto.ReturnSaveRequest;
import com.wille.erp.sale.entity.SalOutbound;
import com.wille.erp.sale.entity.SalOutboundItem;
import com.wille.erp.sale.entity.SalReturn;
import com.wille.erp.sale.entity.SalReturnItem;
import com.wille.erp.sale.mapper.SalOutboundItemMapper;
import com.wille.erp.sale.mapper.SalOutboundMapper;
import com.wille.erp.sale.mapper.SalReturnItemMapper;
import com.wille.erp.sale.mapper.SalReturnMapper;
import com.wille.erp.support.CountLockFixture;
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
 * 销售退货（M5 任务 5，spec §2.5/§8-3/§8-6）：有原单价强制+配额 AUDITED-only+SN 回库；
 * 无原单手录价+既有批次；returnable-sns 按最近出库追溯；作废出库方向回冲。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SaleReturnServiceTest {

    @Autowired
    private SaleReturnService returnService;
    @Autowired
    private SaleOutboundService outboundService;
    @Autowired
    private SalReturnMapper returnMapper;
    @Autowired
    private SalReturnItemMapper returnItemMapper;
    @Autowired
    private SalOutboundMapper outboundMapper;
    @Autowired
    private SalOutboundItemMapper outboundItemMapper;
    @Autowired
    private StockService stockService;
    @Autowired
    private InvStockMapper stockMapper;
    @Autowired
    private InvSnMapper snMapper;
    @Autowired
    private LotApi lotApi;
    @Autowired
    private StockInvariantAsserter asserter;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private CountLockFixture countLockFixture;

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

    private record Ctx(BaseWarehouse wh, BaseCustomer cu, BaseProduct p) {
    }

    /** 建库存（NONE 模式，均价 5） */
    private Ctx ctx(String tag, String stockQty, MgmtMode mode) {
        BaseWarehouse wh = dataFactory.warehouse("SR-W" + tag);
        BaseCustomer cu = dataFactory.customer("SR-C" + tag);
        BaseProduct p = dataFactory.product("SR-P" + tag, mode);
        if (stockQty != null) {
            stockService.inbound(new MoveCmd(wh.getId(),
                    new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 1L, "SR-IN"),
                    List.of(new MoveLine(p.getId(), bd(stockQty), bd("5"), null, null, 1L))));
        }
        return new Ctx(wh, cu, p);
    }

    /** 直接出库（无源）审核，返回出库单 id */
    private Long auditedDirectOutbound(Ctx c, String qty, List<String> sns) {
        OutboundSaveRequest req = new OutboundSaveRequest();
        req.setCustomerId(c.cu().getId());
        req.setWarehouseId(c.wh().getId());
        OutboundItemRequest line = new OutboundItemRequest();
        line.setSkuId(c.p().getId());
        line.setQty(bd(qty));
        line.setPrice(bd("9"));
        line.setSnList(sns);
        req.setItems(List.of(line));
        Long id = outboundService.create(req);
        if (sns != null) {
            // SN 行需先入库存再出库
        }
        outboundService.audit(id);
        return id;
    }

    private SalOutboundItem obLine(Long outboundId) {
        return outboundItemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, outboundId)).get(0);
    }

    private ReturnSaveRequest sourcedReturn(Ctx c, Long outboundId, String qty, BigDecimal price, List<String> sns) {
        ReturnSaveRequest req = new ReturnSaveRequest();
        req.setCustomerId(c.cu().getId());
        req.setWarehouseId(c.wh().getId());
        req.setSourceOutboundId(outboundId);
        ReturnItemRequest line = new ReturnItemRequest();
        line.setSourceOutboundItemId(obLine(outboundId).getId());
        line.setSkuId(c.p().getId());
        line.setQty(bd(qty));
        line.setPrice(price);
        line.setSnList(sns);
        line.setReason("质量不符");
        req.setItems(List.of(line));
        return req;
    }

    private InvStock stockRow(Ctx c) {
        return stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, c.wh().getId()).eq(InvStock::getSkuId, c.p().getId()));
    }

    @Test
    void sourcedReturnCarriesPriceAndQuotaAuditedOnly() {
        Ctx c = ctx("1", "10", MgmtMode.NONE);
        Long ob = auditedDirectOutbound(c, "6", null);
        // 手传价 99 被强制原出库价 9
        Long r1 = returnService.create(sourcedReturn(c, ob, "4", bd("99"), null));
        SalReturnItem row = returnItemMapper.selectList(Wrappers.<SalReturnItem>lambdaQuery()
                .eq(SalReturnItem::getDocId, r1)).get(0);
        assertThat(row.getPrice()).isEqualByComparingTo(bd("9"));
        returnService.audit(r1);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("8"));

        // 草稿退货不占配额：r2 草稿 5 + r3 审核 2 ≤ 6 放行
        returnService.create(sourcedReturn(c, ob, "5", null, null));
        Long r3 = returnService.create(sourcedReturn(c, ob, "2", null, null));
        returnService.audit(r3);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("10"));

        Long r4 = returnService.create(sourcedReturn(c, ob, "3", null, null)); // 4+2+3 > 6
        assertThatThrownBy(() -> returnService.audit(r4))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("累计退货量超过原出库量");
        asserter.assertConsistent();
    }

    @Test
    void snReopenOnAudit() {
        Ctx c = ctx("2", null, MgmtMode.SN);
        stockService.inbound(new MoveCmd(c.wh().getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 2L, "SR-SNIN"),
                List.of(new MoveLine(c.p().getId(), bd("2"), bd("5"), null, List.of("SR-A", "SR-B"), 9L))));
        Long ob = auditedDirectOutbound(c, "2", List.of("SR-A", "SR-B"));

        Long r = returnService.create(sourcedReturn(c, ob, "1", null, List.of("SR-A")));
        returnService.audit(r);

        InvSn a = snMapper.selectOne(Wrappers.<InvSn>lambdaQuery().eq(InvSn::getSnCode, "SR-A"));
        assertThat(a.getStatus()).isEqualTo(SnStatus.IN_STOCK);
        assertThat(a.getWarehouseId()).isEqualTo(c.wh().getId());
        assertThat(a.getInDocId()).isEqualTo(r);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("1"));
        asserter.assertConsistent();
    }

    @Test
    void snMustComeFromReturnableSet() {
        Ctx c = ctx("3", null, MgmtMode.SN);
        stockService.inbound(new MoveCmd(c.wh().getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 3L, "SR-SNIN3"),
                List.of(new MoveLine(c.p().getId(), bd("1"), bd("5"), null, List.of("SR-C"), 9L))));
        Long ob = auditedDirectOutbound(c, "1", List.of("SR-C"));

        ReturnSaveRequest req = sourcedReturn(c, ob, "1", null, List.of("SR-OTHER"));
        Long r = returnService.create(req);
        assertThatThrownBy(() -> returnService.audit(r))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可退");
    }

    @Test
    void returnableSnsFollowLatestOutboundRoundTrip() {
        // 审查重点 4：出库→退→再出库→可退集合按最近出库（out_doc 指向最新单）
        Ctx c = ctx("4", null, MgmtMode.SN);
        stockService.inbound(new MoveCmd(c.wh().getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 4L, "SR-SNIN4"),
                List.of(new MoveLine(c.p().getId(), bd("1"), bd("5"), null, List.of("SR-D"), 9L))));
        Long ob1 = auditedDirectOutbound(c, "1", List.of("SR-D"));
        assertThat(returnService.returnableSns(obLine(ob1).getId())).containsExactly("SR-D");

        Long r = returnService.create(sourcedReturn(c, ob1, "1", null, List.of("SR-D")));
        returnService.audit(r);
        assertThat(returnService.returnableSns(obLine(ob1).getId())).isEmpty();

        Long ob2 = auditedDirectOutbound(c, "1", List.of("SR-D"));
        assertThat(returnService.returnableSns(obLine(ob1).getId())).isEmpty();
        assertThat(returnService.returnableSns(obLine(ob2).getId())).containsExactly("SR-D");
        asserter.assertConsistent();
    }

    @Test
    void noSourceReturnManualPriceAtAverage() {
        Ctx c = ctx("5", "6", MgmtMode.NONE); // 均价 5
        ReturnSaveRequest req = new ReturnSaveRequest();
        req.setCustomerId(c.cu().getId());
        req.setWarehouseId(c.wh().getId());
        ReturnItemRequest line = new ReturnItemRequest();
        line.setSkuId(c.p().getId());
        line.setQty(bd("4"));
        line.setPrice(bd("7"));
        line.setReason("无原单退货");
        req.setItems(List.of(line));
        Long r = returnService.create(req);
        returnService.audit(r);

        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("10"));
        assertThat(returnMapper.selectById(r).getStatus()).isEqualTo(DocStatus.AUDITED);
        asserter.assertConsistent();
    }

    @Test
    void noSourceLotUsesExistingLotOnly() {
        Ctx c = ctx("6", null, MgmtMode.LOT);
        Long lotId = lotApi.materialize(c.p().getId(), "SR-LOT-1", LocalDate.now());
        stockService.inbound(new MoveCmd(c.wh().getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 6L, "SR-LOTIN"),
                List.of(new MoveLine(c.p().getId(), bd("5"), bd("5"), lotId, null, 9L))));

        ReturnSaveRequest req = new ReturnSaveRequest();
        req.setCustomerId(c.cu().getId());
        req.setWarehouseId(c.wh().getId());
        ReturnItemRequest line = new ReturnItemRequest();
        line.setSkuId(c.p().getId());
        line.setQty(bd("2"));
        line.setPrice(bd("5"));
        line.setLotNo("SR-NOPE");
        line.setReason("未知批次");
        req.setItems(List.of(line));
        Long bad = returnService.create(req);
        assertThatThrownBy(() -> returnService.audit(bad))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("批次不存在");

        req.getItems().get(0).setLotNo("SR-LOT-1");
        Long ok = returnService.create(req);
        returnService.audit(ok);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("7"));
        asserter.assertConsistent();
    }

    @Test
    void voidReturnReversesInboundDirection() {
        Ctx c = ctx("7", null, MgmtMode.SN);
        stockService.inbound(new MoveCmd(c.wh().getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 7L, "SR-SNIN7"),
                List.of(new MoveLine(c.p().getId(), bd("2"), bd("5"), null, List.of("SR-E", "SR-F"), 9L))));
        Long ob = auditedDirectOutbound(c, "2", List.of("SR-E", "SR-F"));
        Long r = returnService.create(sourcedReturn(c, ob, "2", null, List.of("SR-E", "SR-F")));
        returnService.audit(r);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("2"));

        returnService.voidReturn(r, "误退");

        assertThat(stockRow(c).getQty()).isEqualByComparingTo(BigDecimal.ZERO);
        InvSn e = snMapper.selectOne(Wrappers.<InvSn>lambdaQuery().eq(InvSn::getSnCode, "SR-E"));
        assertThat(e.getStatus()).isEqualTo(SnStatus.OUT);
        asserter.assertConsistent();
    }

    @Test
    void draftVoidAllowed() {
        Ctx c = ctx("8", "10", MgmtMode.NONE);
        Long ob = auditedDirectOutbound(c, "4", null);
        Long r = returnService.create(sourcedReturn(c, ob, "1", null, null));

        returnService.voidReturn(r, "建错了");

        assertThat(returnMapper.selectById(r).getStatus()).isEqualTo(DocStatus.VOIDED);
        assertThat(stockRow(c).getQty()).isEqualByComparingTo(bd("6"));
        asserter.assertConsistent();
    }

    @Test
    void returnAgainstDraftOutboundRejectedAtCreate() {
        Ctx c = ctx("9", "10", MgmtMode.NONE);
        OutboundSaveRequest obReq = new OutboundSaveRequest();
        obReq.setCustomerId(c.cu().getId());
        obReq.setWarehouseId(c.wh().getId());
        OutboundItemRequest ol = new OutboundItemRequest();
        ol.setSkuId(c.p().getId());
        ol.setQty(bd("4"));
        ol.setPrice(bd("9"));
        obReq.setItems(List.of(ol));
        Long draftOb = outboundService.create(obReq);

        assertThatThrownBy(() -> returnService.create(sourcedReturn(c, draftOb, "1", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审核");
    }

    @Test
    void countLockBlocksReturnAudit() {
        Ctx c = ctx("10", "10", MgmtMode.NONE);
        Long ob = auditedDirectOutbound(c, "4", null);
        Long r = returnService.create(sourcedReturn(c, ob, "2", null, null));
        countLockFixture.counting(c.wh().getId(), c.p().getId());

        assertThatThrownBy(() -> returnService.audit(r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.COUNT_LOCK_CONFLICT);
    }

    @Test
    void returnableSnsAndPageRespectWarehouseScope() {
        // 审查 I2/I4：returnable-sns 跨仓拒 + 退货 page 过滤
        Ctx a = ctx("12A", "10", MgmtMode.NONE);
        Ctx b = ctx("12B", "10", MgmtMode.NONE);
        Long obB = auditedDirectOutbound(b, "2", null);
        returnService.create(sourcedReturn(a, auditedDirectOutbound(a, "2", null), "1", null, null));

        dataFactory.roleWithPerms("R_SRSCOPE", "退货范围", "sale:return:create", "sale:return:list");
        var u = dataFactory.user("srscope01", "pwd123", "R_SRSCOPE");
        dataFactory.bindWarehouses(u.getId(), a.wh().getId());
        StpUtil.logout();
        StpUtil.login(u.getId());

        assertThatThrownBy(() -> returnService.returnableSns(obLine(obB).getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NO_PERMISSION);
        assertThat(returnService.page(1, 20, null).getRecords())
                .allSatisfy(r -> assertThat(r.getWarehouseId()).isEqualTo(a.wh().getId()));
    }

    @Test
    void createFromOutboundCarriesRemaining() {
        Ctx c = ctx("11", "10", MgmtMode.NONE);
        Long ob = auditedDirectOutbound(c, "6", null);
        Long r1 = returnService.create(sourcedReturn(c, ob, "2", null, null));
        returnService.audit(r1);

        Long draftId = returnService.createFromOutbound(ob);

        SalReturn doc = returnMapper.selectById(draftId);
        assertThat(doc.getSourceOutboundId()).isEqualTo(ob);
        assertThat(doc.getStatus()).isEqualTo(DocStatus.DRAFT);
        SalReturnItem row = returnItemMapper.selectList(Wrappers.<SalReturnItem>lambdaQuery()
                .eq(SalReturnItem::getDocId, draftId)).get(0);
        assertThat(row.getQty()).isEqualByComparingTo(bd("4")); // 6 − 已退 2
        assertThat(row.getPrice()).isEqualByComparingTo(bd("9"));
        assertThat(row.getReason()).isNotBlank();
    }
}
