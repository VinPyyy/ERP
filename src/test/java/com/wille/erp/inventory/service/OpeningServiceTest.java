package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.dto.OpeningItemRequest;
import com.wille.erp.inventory.dto.OpeningSaveRequest;
import com.wille.erp.inventory.entity.InvFlow;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.entity.InvLotStock;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvFlowMapper;
import com.wille.erp.inventory.mapper.InvLotMapper;
import com.wille.erp.inventory.mapper.InvLotStockMapper;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.support.DataFactory;
import com.wille.erp.support.StockInvariantAsserter;
import cn.dev33.satoken.stp.StpUtil;
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
 * 期初单全生命周期（M3b 任务 1，spec §2.3/§3b/§3c/§4.2）。
 * 每个审核/作废用例收尾跑四层不变量。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OpeningServiceTest {

    @Autowired
    private OpeningService openingService;
    @Autowired
    private StockService stockService;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private StockInvariantAsserter asserter;
    @Autowired
    private InvStockMapper stockMapper;
    @Autowired
    private InvFlowMapper flowMapper;
    @Autowired
    private InvLotMapper lotMapper;
    @Autowired
    private InvLotStockMapper lotStockMapper;
    @Autowired
    private InvSnMapper snMapper;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 服务层数据权限依赖登录上下文：绑 admin（V2 种子绑定全部仓库） */
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

    private OpeningSaveRequest req(Long warehouseId, OpeningItemRequest... items) {
        OpeningSaveRequest request = new OpeningSaveRequest();
        request.setWarehouseId(warehouseId);
        request.setItems(List.of(items));
        return request;
    }

    private OpeningItemRequest item(Long skuId, String qty, String price) {
        OpeningItemRequest i = new OpeningItemRequest();
        i.setSkuId(skuId);
        i.setQty(bd(qty));
        i.setCostPrice(bd(price));
        return i;
    }

    private InvStock stock(Long wh, Long sku) {
        return stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, wh).eq(InvStock::getSkuId, sku));
    }

    @Test
    void createDraftAssignsQcDocNo() {
        BaseWarehouse wh = dataFactory.warehouse("OP-C1");
        BaseProduct p = dataFactory.product("OP-P1", MgmtMode.NONE);

        Long id = openingService.create(req(wh.getId(), item(p.getId(), "10", "5")));

        var vo = openingService.get(id);
        assertThat(vo.getDocNo()).startsWith("QC");
        assertThat(vo.getStatus()).isEqualTo(10);
    }

    @Test
    void auditMovesStockWritesOpeningFlipsSnAndMaterializesLot() {
        BaseWarehouse wh = dataFactory.warehouse("OP-A1");
        BaseProduct plain = dataFactory.product("OP-PL", MgmtMode.NONE);
        BaseProduct lotSn = dataFactory.product("OP-LS", MgmtMode.LOT_SN);

        OpeningItemRequest plainItem = item(plain.getId(), "10", "5");
        OpeningItemRequest snItem = item(lotSn.getId(), "2", "30");
        snItem.setLotNo("L1");
        snItem.setProductionDate(java.time.LocalDate.of(2026, 1, 1));
        snItem.setSnList(List.of("S1", "S2"));

        Long id = openingService.create(req(wh.getId(), plainItem, snItem));
        openingService.audit(id);

        InvStock s = stock(wh.getId(), plain.getId());
        assertThat(s.getQty()).isEqualByComparingTo(bd("10"));
        assertThat(s.getCostAmount()).isEqualByComparingTo(bd("50"));

        InvFlow flow = flowMapper.selectOne(Wrappers.<InvFlow>lambdaQuery()
                .eq(InvFlow::getBizDocType, DocType.OPENING).eq(InvFlow::getBizDocId, id)
                .eq(InvFlow::getSkuId, plain.getId()));
        assertThat(flow.getBizType()).isEqualTo(BizType.OPENING);

        InvLot lot = lotMapper.selectOne(Wrappers.<InvLot>lambdaQuery()
                .eq(InvLot::getSkuId, lotSn.getId()).eq(InvLot::getLotNo, "L1"));
        assertThat(lot).isNotNull();
        InvLotStock lotStock = lotStockMapper.selectOne(Wrappers.<InvLotStock>lambdaQuery()
                .eq(InvLotStock::getLotId, lot.getId()));
        assertThat(lotStock.getQty()).isEqualByComparingTo(bd("2"));

        List<InvSn> sns = snMapper.selectList(Wrappers.<InvSn>lambdaQuery().eq(InvSn::getSkuId, lotSn.getId()));
        assertThat(sns).hasSize(2);
        assertThat(sns).allSatisfy(sn -> {
            assertThat(sn.getStatus()).isEqualTo(SnStatus.IN_STOCK);
            assertThat(sn.getWarehouseId()).isEqualTo(wh.getId());
            assertThat(sn.getInDocNo()).startsWith("QC");
        });

        asserter.assertConsistent();
    }

    @Test
    void duplicateOpeningRowRejected() {
        BaseWarehouse wh = dataFactory.warehouse("OP-D1");
        BaseProduct p = dataFactory.product("OP-DP", MgmtMode.NONE);
        Long first = openingService.create(req(wh.getId(), item(p.getId(), "10", "5")));
        openingService.audit(first);

        Long second = openingService.create(req(wh.getId(), item(p.getId(), "3", "7")));

        assertThatThrownBy(() -> openingService.audit(second))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("期初");
    }

    @Test
    void auditTwiceRejectedByConditionalUpdate() {
        BaseWarehouse wh = dataFactory.warehouse("OP-T1");
        BaseProduct p = dataFactory.product("OP-TP", MgmtMode.NONE);
        Long id = openingService.create(req(wh.getId(), item(p.getId(), "10", "5")));
        openingService.audit(id);

        assertThatThrownBy(() -> openingService.audit(id))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    }

    @Test
    void voidWithSubsequentFlowRejected() {
        BaseWarehouse wh = dataFactory.warehouse("OP-V1");
        BaseProduct p = dataFactory.product("OP-VP", MgmtMode.NONE);
        Long id = openingService.create(req(wh.getId(), item(p.getId(), "10", "5")));
        openingService.audit(id);

        // 后续采购入库消耗了该 wh×SKU
        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 555L, "SUBSEQ"),
                List.of(new MoveLine(p.getId(), bd("5"), bd("6"), null, null, 1L))));

        assertThatThrownBy(() -> openingService.voidOpening(id, "尝试作废"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.VOID_STOCK_CONSUMED);
    }

    @Test
    void voidReversesBySnapshot() {
        BaseWarehouse wh = dataFactory.warehouse("OP-R1");
        BaseProduct p = dataFactory.product("OP-RP", MgmtMode.SN);
        OpeningItemRequest it = item(p.getId(), "2", "9");
        it.setSnList(List.of("R1A", "R1B"));
        Long id = openingService.create(req(wh.getId(), it));
        openingService.audit(id);

        openingService.voidOpening(id, "录错单价");

        assertThat(stock(wh.getId(), p.getId()).getQty()).isEqualByComparingTo(BigDecimal.ZERO);
        List<InvFlow> reverses = flowMapper.selectList(Wrappers.<InvFlow>lambdaQuery()
                .eq(InvFlow::getBizDocType, DocType.OPENING).eq(InvFlow::getBizDocId, id)
                .isNotNull(InvFlow::getReverseFlowId));
        assertThat(reverses).hasSize(1);
        assertThat(reverses.get(0).getQty()).isEqualByComparingTo(bd("-2"));
        assertThat(reverses.get(0).getCostAmount()).isEqualByComparingTo(bd("-18"));
        // 本单新建的 SN 被删除
        assertThat(snMapper.selectCount(Wrappers.<InvSn>lambdaQuery().eq(InvSn::getSkuId, p.getId()))).isZero();
        assertThat(openingService.get(id).getStatus()).isEqualTo(50);
        asserter.assertConsistent();
    }

    @Test
    void voidTwiceRejected() {
        BaseWarehouse wh = dataFactory.warehouse("OP-W1");
        BaseProduct p = dataFactory.product("OP-WP", MgmtMode.NONE);
        Long id = openingService.create(req(wh.getId(), item(p.getId(), "10", "5")));
        openingService.audit(id);
        openingService.voidOpening(id, "第一次作废");

        assertThatThrownBy(() -> openingService.voidOpening(id, "再来一次"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void voidRequiresReason() {
        BaseWarehouse wh = dataFactory.warehouse("OP-W2");
        BaseProduct p = dataFactory.product("OP-WQ", MgmtMode.NONE);
        Long id = openingService.create(req(wh.getId(), item(p.getId(), "10", "5")));
        openingService.audit(id);

        assertThatThrownBy(() -> openingService.voidOpening(id, " "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("原因");
    }

    // ===== 最终审查修复轮回归（C1/I1/I3/I4/I5/I7/I8/I9） =====

    @Test
    void snReturnReversalRestoresOutNotDelete() {
        // C1：翻回的 SN（曾出库）不得被物理删除，冲正应恢复 OUT 态
        BaseWarehouse wh = dataFactory.warehouse("OP-C1");
        BaseProduct p = dataFactory.product("OP-CP", MgmtMode.SN);
        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 801L, "C1-IN"),
                List.of(new MoveLine(p.getId(), bd("1"), bd("9"), null, List.of("C1SN"), 1L))));
        stockService.outbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.SALE_OUT, DocType.SAL_OUTBOUND, 802L, "C1-OUT"),
                List.of(new MoveLine(p.getId(), bd("1"), null, null, List.of("C1SN"), 1L))));
        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.SAL_RETURN_IN, DocType.SAL_RETURN, 803L, "C1-RTN"),
                List.of(new MoveLine(p.getId(), bd("1"), bd("9"), null, List.of("C1SN"), 1L))));

        stockService.reverse(new com.wille.erp.inventory.api.StockCmd.ReverseCmd(DocType.SAL_RETURN, 803L));

        InvSn sn = snMapper.selectOne(Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSkuId, p.getId()).eq(InvSn::getSnCode, "C1SN"));
        assertThat(sn).as("翻回 SN 冲正后必须仍存在").isNotNull();
        assertThat(sn.getStatus()).isEqualTo(SnStatus.OUT);
        assertThat(sn.getWarehouseId()).isNull();
        asserter.assertConsistent();
    }

    @Test
    void reverseTwiceRejectedOnFacade() {
        // I1：reverse_flow_id 已占用 → 拒绝二次冲正
        BaseWarehouse wh = dataFactory.warehouse("OP-I1");
        BaseProduct p = dataFactory.product("OP-I1P", MgmtMode.NONE);
        Long id = openingService.create(req(wh.getId(), item(p.getId(), "10", "5")));
        openingService.audit(id);

        stockService.reverse(new com.wille.erp.inventory.api.StockCmd.ReverseCmd(DocType.OPENING, id));
        assertThatThrownBy(() ->
                stockService.reverse(new com.wille.erp.inventory.api.StockCmd.ReverseCmd(DocType.OPENING, id)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    }

    @Test
    void auditForbiddenForInvisibleWarehouse() {
        // I3：数据权限——绑仓 A 的用户不得审核仓 B 的期初单
        BaseWarehouse whA = dataFactory.warehouse("OP-I3A");
        BaseWarehouse whB = dataFactory.warehouse("OP-I3B");
        BaseProduct p = dataFactory.product("OP-I3P", MgmtMode.NONE);
        Long docId = openingService.create(req(whB.getId(), item(p.getId(), "10", "5")));

        var user = dataFactory.user("op-i3u", "pwd123", "SALES");
        dataFactory.bindWarehouses(user.getId(), whA.getId());
        StpUtil.logout();
        StpUtil.login(user.getId());

        assertThatThrownBy(() -> openingService.audit(docId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NO_PERMISSION);
        assertThat(whA.getId()).isNotNull();
    }

    @Test
    void pageEmptyForUserWithoutWarehouses() {
        // I4(a)：零仓库用户 page 不得 SQL 崩溃，应返回空页
        var user = dataFactory.user("op-i4u", "pwd123", "SALES");
        StpUtil.logout();
        StpUtil.login(user.getId());

        var result = openingService.page(1, 20);
        assertThat(result.getTotal()).isZero();
    }

    @Test
    void voidAllowedAfterDownstreamReversal() {
        // I8：净零流水对（原+已冲正）不得永久阻断期初作废
        BaseWarehouse wh = dataFactory.warehouse("OP-I8");
        BaseProduct p = dataFactory.product("OP-I8P", MgmtMode.NONE);
        Long openingId = openingService.create(req(wh.getId(), item(p.getId(), "10", "5")));
        openingService.audit(openingId);

        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 901L, "I8-IN"),
                List.of(new MoveLine(p.getId(), bd("5"), bd("6"), null, null, 1L))));
        assertThatThrownBy(() -> openingService.voidOpening(openingId, "先试"))
                .isInstanceOf(BizException.class);

        stockService.reverse(new com.wille.erp.inventory.api.StockCmd.ReverseCmd(DocType.PUR_INBOUND, 901L));
        openingService.voidOpening(openingId, "下游已作废");
        assertThat(openingService.get(openingId).getStatus()).isEqualTo(50);
        asserter.assertConsistent();
    }

    @Test
    void voidLotManagedOpeningReversesLotStock() {
        // I9：管批次期初的批次行回冲必须有覆盖
        BaseWarehouse wh = dataFactory.warehouse("OP-I9");
        BaseProduct p = dataFactory.product("OP-I9P", MgmtMode.LOT);
        OpeningItemRequest it = item(p.getId(), "10", "4");
        it.setLotNo("I9L");
        it.setProductionDate(java.time.LocalDate.of(2026, 2, 2));
        Long id = openingService.create(req(wh.getId(), it));
        openingService.audit(id);

        openingService.voidOpening(id, "批次测试作废");

        assertThat(stock(wh.getId(), p.getId()).getQty()).isEqualByComparingTo(BigDecimal.ZERO);
        InvLot lot = lotMapper.selectOne(Wrappers.<InvLot>lambdaQuery()
                .eq(InvLot::getSkuId, p.getId()).eq(InvLot::getLotNo, "I9L"));
        assertThat(lotStockMapper.selectOne(Wrappers.<InvLotStock>lambdaQuery()
                .eq(InvLotStock::getLotId, lot.getId())).getQty()).isEqualByComparingTo(BigDecimal.ZERO);
        asserter.assertConsistent();
    }

    @Test
    void lotManagedOpeningRequiresProductionDate() {
        // I7：spec §2.3 管批次必填生产日期（两层）
        BaseWarehouse wh = dataFactory.warehouse("OP-I7");
        BaseProduct p = dataFactory.product("OP-I7P", MgmtMode.LOT);
        OpeningItemRequest it = item(p.getId(), "10", "4");
        it.setLotNo("I7L");
        // 无生产日期

        assertThatThrownBy(() -> openingService.create(req(wh.getId(), it)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("生产日期");
    }
}
