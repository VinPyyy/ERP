package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockCmd;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
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
import com.wille.erp.system.api.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StockService in/out 集成测试族（M3a 任务 3，spec §3a/§3b/§3f）。
 * 并发首行 upsert 用例在 StockServiceConcurrencyTest（需要真实多事务，不能套测试事务回滚）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StockServiceMoveTest {

    @Autowired
    private StockService stockService;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private InvStockMapper stockMapper;
    @Autowired
    private InvLotStockMapper lotStockMapper;
    @Autowired
    private InvLotMapper lotMapper;
    @Autowired
    private InvSnMapper snMapper;
    @Autowired
    private InvFlowMapper flowMapper;
    @Autowired
    private ConfigService configService;

    private static final BizRef REF = new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 999L, "TEST-IN-001");

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private InvStock stock(Long wh, Long sku) {
        return stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, wh).eq(InvStock::getSkuId, sku));
    }

    private List<InvFlow> flows(Long wh, Long sku) {
        return flowMapper.selectList(Wrappers.<InvFlow>lambdaQuery()
                .eq(InvFlow::getWarehouseId, wh).eq(InvFlow::getSkuId, sku).orderByAsc(InvFlow::getId));
    }

    private void inbound(Long wh, MoveLine... lines) {
        stockService.inbound(new MoveCmd(wh, REF, List.of(lines)));
    }

    private void outbound(Long wh, MoveLine... lines) {
        stockService.outbound(new MoveCmd(wh, new BizRef(BizType.SALE_OUT, DocType.SAL_OUTBOUND, 888L, "TEST-OUT-001"),
                List.of(lines)));
    }

    private Long newLot(Long skuId, String no) {
        InvLot lot = new InvLot();
        lot.setSkuId(skuId);
        lot.setLotNo(no);
        lotMapper.insert(lot);
        return lot.getId();
    }

    @Test
    void specSampleInIn10at10In5at13Out8() {
        BaseWarehouse wh = dataFactory.warehouse("WH-SPEC");
        BaseProduct p = dataFactory.product("SKU-SPEC", MgmtMode.NONE);

        inbound(wh.getId(), new MoveLine(p.getId(), bd("10"), bd("10"), null, null, 1L));
        inbound(wh.getId(), new MoveLine(p.getId(), bd("5"), bd("13"), null, null, 2L));
        outbound(wh.getId(), new MoveLine(p.getId(), bd("8"), null, null, null, 3L));

        InvStock s = stock(wh.getId(), p.getId());
        assertThat(s.getQty()).isEqualByComparingTo(bd("7"));
        assertThat(s.getCostAmount()).isEqualByComparingTo(bd("77"));
        assertThat(s.getCostPrice()).isEqualByComparingTo(bd("11"));
    }

    @Test
    void upsertCreatesRowOnFirstInbound() {
        BaseWarehouse wh = dataFactory.warehouse("WH-UPSERT");
        BaseProduct p = dataFactory.product("SKU-UPSERT", MgmtMode.NONE);

        inbound(wh.getId(), new MoveLine(p.getId(), bd("3"), bd("7"), null, null, 1L));

        assertThat(stock(wh.getId(), p.getId()).getQty()).isEqualByComparingTo(bd("3"));
    }

    @Test
    void outboundAtAverageKeepsPrice() {
        BaseWarehouse wh = dataFactory.warehouse("WH-KEEPPRICE");
        BaseProduct p = dataFactory.product("SKU-KEEPPRICE", MgmtMode.NONE);
        inbound(wh.getId(), new MoveLine(p.getId(), bd("10"), bd("10"), null, null, 1L));

        outbound(wh.getId(), new MoveLine(p.getId(), bd("4"), null, null, null, 2L));

        InvStock s = stock(wh.getId(), p.getId());
        assertThat(s.getCostPrice()).isEqualByComparingTo(bd("10"));
        assertThat(s.getCostAmount()).isEqualByComparingTo(bd("60"));
    }

    @Test
    void clearingSweepsResidue() {
        BaseWarehouse wh = dataFactory.warehouse("WH-CLEAR");
        BaseProduct p = dataFactory.product("SKU-CLEAR", MgmtMode.NONE);
        inbound(wh.getId(), new MoveLine(p.getId(), bd("10"), bd("10.05"), null, null, 1L));
        inbound(wh.getId(), new MoveLine(p.getId(), bd("5"), bd("13"), null, null, 2L));

        outbound(wh.getId(), new MoveLine(p.getId(), bd("15"), null, null, null, 3L));

        InvStock s = stock(wh.getId(), p.getId());
        assertThat(s.getQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.getCostAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.getCostPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void negativeSwitchMatrix() {
        BaseWarehouse wh = dataFactory.warehouse("WH-NEG");
        BaseProduct p = dataFactory.product("SKU-NEG", MgmtMode.NONE);
        inbound(wh.getId(), new MoveLine(p.getId(), bd("5"), bd("10"), null, null, 1L));

        // 默认 false：超出现有量拒绝
        assertThatThrownBy(() -> outbound(wh.getId(), new MoveLine(p.getId(), bd("6"), null, null, null, 2L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不足");

        // 开关放开：允许负库存
        configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "true");
        try {
            outbound(wh.getId(), new MoveLine(p.getId(), bd("6"), null, null, null, 3L));
            assertThat(stock(wh.getId(), p.getId()).getQty()).isEqualByComparingTo(bd("-1"));
        } finally {
            configService.update(ConfigService.KEY_ALLOW_NEGATIVE, "false");
        }
    }

    @Test
    void lotManagedRequiresLotAndKeepsInvariant() {
        BaseWarehouse wh = dataFactory.warehouse("WH-LOT");
        BaseProduct p = dataFactory.product("SKU-LOT", MgmtMode.LOT);
        Long lot = newLot(p.getId(), "L1");

        // 缺 lotId 拒绝
        assertThatThrownBy(() -> inbound(wh.getId(), new MoveLine(p.getId(), bd("10"), bd("5"), null, null, 1L)))
                .isInstanceOf(BizException.class);

        inbound(wh.getId(), new MoveLine(p.getId(), bd("10"), bd("5"), lot, null, 1L));
        assertThat(lotStock(wh.getId(), p.getId(), lot).getQty()).isEqualByComparingTo(bd("10"));

        // 批次不足要隔离总量检查：从空批次 L2 出 6（总量 10 够、L2 不够）→ 30006
        Long lot2 = newLot(p.getId(), "L2");
        assertThatThrownBy(() -> outbound(wh.getId(), new MoveLine(p.getId(), bd("6"), null, lot2, null, 2L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("批次");

        // 正常出 6：批次行与主行 SUM 不变量
        outbound(wh.getId(), new MoveLine(p.getId(), bd("6"), null, lot, null, 3L));
        assertThat(lotStock(wh.getId(), p.getId(), lot).getQty()).isEqualByComparingTo(bd("4"));
        assertThat(stock(wh.getId(), p.getId()).getQty()).isEqualByComparingTo(bd("4"));
    }

    private InvLotStock lotStock(Long wh, Long sku, Long lot) {
        return lotStockMapper.selectOne(Wrappers.<InvLotStock>lambdaQuery()
                .eq(InvLotStock::getWarehouseId, wh).eq(InvLotStock::getSkuId, sku).eq(InvLotStock::getLotId, lot));
    }

    @Test
    void snMaterializeFlipAndReject() {
        BaseWarehouse wh = dataFactory.warehouse("WH-SN");
        BaseWarehouse wh2 = dataFactory.warehouse("WH-SN2");
        BaseProduct p = dataFactory.product("SKU-SN", MgmtMode.SN);

        // 首次入库物化
        inbound(wh.getId(), new MoveLine(p.getId(), bd("2"), bd("9"), null, List.of("A", "B"), 1L));
        InvSn a = sn(p.getId(), "A");
        assertThat(a.getStatus()).isEqualTo(SnStatus.IN_STOCK);
        assertThat(a.getWarehouseId()).isEqualTo(wh.getId());
        assertThat(a.getInDocNo()).isEqualTo("TEST-IN-001");

        // 重复在库 → 30005
        assertThatThrownBy(() -> inbound(wh.getId(), new MoveLine(p.getId(), bd("1"), bd("9"), null, List.of("A"), 2L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复");

        // 出库核销
        outbound(wh.getId(), new MoveLine(p.getId(), bd("1"), null, null, List.of("A"), 3L));
        a = sn(p.getId(), "A");
        assertThat(a.getStatus()).isEqualTo(SnStatus.OUT);
        assertThat(a.getWarehouseId()).isNull();
        assertThat(a.getOutDocNo()).isEqualTo("TEST-OUT-001");

        // 已 OUT 再出 → 30004
        assertThatThrownBy(() -> outbound(wh.getId(), new MoveLine(p.getId(), bd("1"), null, null, List.of("A"), 4L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SN");

        // 退货回库：翻回 IN_STOCK 且仓库更新为退货仓
        stockService.inbound(new MoveCmd(wh2.getId(),
                new BizRef(BizType.SAL_RETURN_IN, DocType.SAL_RETURN, 777L, "TEST-RTN-001"),
                List.of(new MoveLine(p.getId(), bd("1"), bd("9"), null, List.of("A"), 5L))));
        a = sn(p.getId(), "A");
        assertThat(a.getStatus()).isEqualTo(SnStatus.IN_STOCK);
        assertThat(a.getWarehouseId()).isEqualTo(wh2.getId());
        assertThat(a.getInDocNo()).isEqualTo("TEST-RTN-001");
    }

    private InvSn sn(Long skuId, String code) {
        return snMapper.selectOne(Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSkuId, skuId).eq(InvSn::getSnCode, code));
    }

    @Test
    void flowSnapshotFieldsComplete() {
        BaseWarehouse wh = dataFactory.warehouse("WH-FLOW");
        BaseProduct p = dataFactory.product("SKU-FLOW", MgmtMode.NONE);

        inbound(wh.getId(), new MoveLine(p.getId(), bd("10"), bd("10"), null, null, 1L));
        inbound(wh.getId(), new MoveLine(p.getId(), bd("5"), bd("13"), null, null, 2L));
        outbound(wh.getId(), new MoveLine(p.getId(), bd("8"), bd("20"), null, null, 3L));

        List<InvFlow> fs = flows(wh.getId(), p.getId());
        assertThat(fs).hasSize(3);
        // 链：balance_* == 上一行 + 本行带符号变动
        assertThat(fs.get(0).getQty()).isEqualByComparingTo(bd("10"));
        assertThat(fs.get(0).getBalanceQty()).isEqualByComparingTo(bd("10"));
        assertThat(fs.get(0).getCostAmount()).isEqualByComparingTo(bd("100"));
        assertThat(fs.get(0).getCostPrice()).isEqualByComparingTo(bd("10"));
        assertThat(fs.get(0).getBizType()).isEqualTo(BizType.PUR_IN);
        assertThat(fs.get(0).getBizDocNo()).isEqualTo("TEST-IN-001");
        assertThat(fs.get(0).getBizDocItemId()).isEqualTo(1L);

        assertThat(fs.get(1).getBalanceQty()).isEqualByComparingTo(bd("15"));
        assertThat(fs.get(1).getBalanceCostAmount()).isEqualByComparingTo(bd("165"));

        // 出库行：qty/cost_amount 带符号；price=业务价两线分离；cost_price=结转均价快照
        assertThat(fs.get(2).getQty()).isEqualByComparingTo(bd("-8"));
        assertThat(fs.get(2).getPrice()).isEqualByComparingTo(bd("20"));
        assertThat(fs.get(2).getCostPrice()).isEqualByComparingTo(bd("11"));
        assertThat(fs.get(2).getCostAmount()).isEqualByComparingTo(bd("-88"));
        assertThat(fs.get(2).getBalanceQty()).isEqualByComparingTo(bd("7"));
        assertThat(fs.get(2).getBalanceCostAmount()).isEqualByComparingTo(bd("77"));
        assertThat(fs.get(2).getBizType()).isEqualTo(BizType.SALE_OUT);
    }

    @Test
    void availableIsQtyMinusLocked() {
        BaseWarehouse wh = dataFactory.warehouse("WH-AVAIL");
        BaseProduct p = dataFactory.product("SKU-AVAIL", MgmtMode.NONE);
        inbound(wh.getId(), new MoveLine(p.getId(), bd("10"), bd("5"), null, null, 1L));

        // 任务 4 前 locked 恒 0：available = qty
        assertThat(stockService.available(wh.getId(), p.getId())).isEqualByComparingTo(bd("10"));
        // 无行仓库返回 0
        assertThat(stockService.available(999999L, p.getId())).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
