package com.wille.erp.inventory.service;

import cn.dev33.satoken.stp.StpUtil;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.LockCmd;
import com.wille.erp.inventory.api.StockCmd.LockLine;
import com.wille.erp.inventory.api.StockCmd.LockRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.dto.FlowQuery;
import com.wille.erp.inventory.dto.SnAdjustRequest;
import com.wille.erp.inventory.dto.SnQuery;
import com.wille.erp.inventory.dto.StockQuery;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.support.DataFactory;
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
 * 库存查询服务（M3c 任务 1）：成本脱敏、仓库数据权限、可用量派生、SN 调整留痕、流水过滤。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryQueryServiceTest {

    @Autowired
    private InventoryQueryService queryService;
    @Autowired
    private StockService stockService;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private InvSnMapper snMapper;

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

    private void loginAs(String username) {
        StpUtil.logout();
        StpUtil.login(dataFactoryUserId(username));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.wille.erp.system.mapper.SysUserMapper userMapper;

    private Long dataFactoryUserId(String username) {
        var u = userMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.
                <com.wille.erp.system.entity.SysUser>lambdaQuery()
                .eq(com.wille.erp.system.entity.SysUser::getUsername, username));
        return u.getId();
    }

    private BaseProduct seedStock(BaseWarehouse wh, BaseProduct p, String qty, String price) {
        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 1L, "Q-IN"),
                List.of(new MoveLine(p.getId(), new BigDecimal(qty), new BigDecimal(price), null, null, 1L))));
        return p;
    }

    @Test
    void costColumnMaskedWithoutPerm() {
        BaseWarehouse wh = dataFactory.warehouse("QW-COST");
        BaseProduct p = dataFactory.product("QP-COST", MgmtMode.NONE);
        seedStock(wh, p, "10", "5");

        // 有 list 无 cost:view 的角色
        dataFactory.roleWithPerms("R_QCOST", "查询无成本", "inventory:stock:list");
        var u = dataFactory.user("qcost01", "pwd123", "R_QCOST");
        dataFactory.bindWarehouses(u.getId(), 0L);
        loginAs("qcost01");

        var page = queryService.pageStocks(new StockQuery());
        var row = page.getRecords().stream().filter(r -> r.getSkuId().equals(p.getId())).findFirst().orElseThrow();
        assertThat(row.getQty()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(row.getCostPrice()).isNull();
        assertThat(row.getCostAmount()).isNull();

        // admin 可见
        loginAs("admin");
        var adminRow = queryService.pageStocks(new StockQuery()).getRecords().stream()
                .filter(r -> r.getSkuId().equals(p.getId())).findFirst().orElseThrow();
        assertThat(adminRow.getCostAmount()).isEqualByComparingTo(new BigDecimal("50"));
    }

    @Test
    void warehouseScopeApplied() {
        BaseWarehouse whA = dataFactory.warehouse("QW-A");
        BaseWarehouse whB = dataFactory.warehouse("QW-B");
        BaseProduct p = dataFactory.product("QP-SCOPE", MgmtMode.NONE);
        seedStock(whA, p, "5", "2");
        seedStock(whB, p, "7", "2");

        dataFactory.roleWithPerms("R_QSCOPE", "范围查询", "inventory:stock:list", "inventory:cost:view");
        var u = dataFactory.user("qscope01", "pwd123", "R_QSCOPE");
        dataFactory.bindWarehouses(u.getId(), whA.getId());
        loginAs("qscope01");

        var rows = queryService.pageStocks(new StockQuery()).getRecords();
        assertThat(rows).allSatisfy(r -> assertThat(r.getWarehouseId()).isEqualTo(whA.getId()));

        // 零仓库用户查空
        var u0 = dataFactory.user("qscope02", "pwd123", "R_QSCOPE");
        loginAs("qscope02");
        assertThat(queryService.pageStocks(new StockQuery()).getRecords()).isEmpty();
    }

    @Test
    void availableQtyDerived() {
        BaseWarehouse wh = dataFactory.warehouse("QW-AVAIL");
        BaseProduct p = dataFactory.product("QP-AVAIL", MgmtMode.NONE);
        seedStock(wh, p, "10", "5");
        stockService.lock(new LockCmd(wh.getId(), new LockRef(DocType.SAL_ORDER, 1L, 1L),
                List.of(new LockLine(p.getId(), new BigDecimal("6")))));

        var row = queryService.pageStocks(new StockQuery()).getRecords().stream()
                .filter(r -> r.getSkuId().equals(p.getId())).findFirst().orElseThrow();
        assertThat(row.getQty()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(row.getLockedQty()).isEqualByComparingTo(new BigDecimal("6"));
        assertThat(row.getAvailableQty()).isEqualByComparingTo(new BigDecimal("4"));
    }

    @Test
    void snAdjustRequiresReason() {
        BaseWarehouse wh = dataFactory.warehouse("QW-SN1");
        BaseProduct p = dataFactory.product("QP-SN1", MgmtMode.SN);
        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 2L, "Q-SNIN"),
                List.of(new MoveLine(p.getId(), new BigDecimal("1"), new BigDecimal("9"), null, List.of("QSN1"), 1L))));
        InvSn sn = snMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSnCode, "QSN1"));

        SnAdjustRequest req = new SnAdjustRequest();
        req.setStatus("OUT");
        assertThatThrownBy(() -> queryService.adjustSn(sn.getId(), req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("原因");
    }

    @Test
    void snAdjustFlipsStateAndAudits() {
        BaseWarehouse wh = dataFactory.warehouse("QW-SN2");
        BaseProduct p = dataFactory.product("QP-SN2", MgmtMode.SN);
        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 3L, "Q-SNIN2"),
                List.of(new MoveLine(p.getId(), new BigDecimal("1"), new BigDecimal("9"), null, List.of("QSN2"), 1L))));
        InvSn sn = snMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSnCode, "QSN2"));

        SnAdjustRequest req = new SnAdjustRequest();
        req.setStatus("OUT");
        req.setReason("实物已寄样未走单");
        queryService.adjustSn(sn.getId(), req);

        InvSn after = snMapper.selectById(sn.getId());
        assertThat(after.getStatus()).isEqualTo(SnStatus.OUT);
        assertThat(after.getWarehouseId()).isNull();
        assertThat(after.getRemark()).isEqualTo("实物已寄样未走单");

        SnQuery q = new SnQuery();
        q.setSkuId(p.getId());
        assertThat(queryService.pageSns(q).getRecords()).hasSize(1);
    }

    @Test
    void pageFlowsWithoutFilterOk() {
        // 审查 C1：无 bizType 的默认加载不得 NPE
        BaseWarehouse wh = dataFactory.warehouse("QW-NOF");
        BaseProduct p = dataFactory.product("QP-NOF", MgmtMode.NONE);
        seedStock(wh, p, "10", "5");

        var page = queryService.pageFlows(new FlowQuery());
        assertThat(page.getRecords()).isNotEmpty();
    }

    @Test
    void illegalBizTypeRejected() {
        FlowQuery q = new FlowQuery();
        q.setBizType("NOT_A_TYPE");
        assertThatThrownBy(() -> queryService.pageFlows(q))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("业务类型");
    }

    @Test
    void flowCostColumnsMaskedWithoutPerm() {
        BaseWarehouse wh = dataFactory.warehouse("QW-FMASK");
        BaseProduct p = dataFactory.product("QP-FMASK", MgmtMode.NONE);
        seedStock(wh, p, "10", "5");

        dataFactory.roleWithPerms("R_QFLOW", "流水无成本", "inventory:flow:list");
        var u = dataFactory.user("qflow01", "pwd123", "R_QFLOW");
        dataFactory.bindWarehouses(u.getId(), 0L);
        loginAs("qflow01");

        var flow = queryService.pageFlows(new FlowQuery()).getRecords().stream()
                .filter(f -> f.getSkuId().equals(p.getId())).findFirst().orElseThrow();
        assertThat(flow.getQty()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(flow.getBalanceQty()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(flow.getCostPrice()).isNull();
        assertThat(flow.getCostAmount()).isNull();
        assertThat(flow.getBalanceCostAmount()).isNull();
        assertThat(flow.getBalanceCostPrice()).isNull();
    }

    @Test
    void adjustSnRequiresSourceWarehouseVisible() {
        // 审查 C3：绑仓 A 用户不得调整仓 B 的在库 SN
        BaseWarehouse whA = dataFactory.warehouse("QW-XA");
        BaseWarehouse whB = dataFactory.warehouse("QW-XB");
        BaseProduct p = dataFactory.product("QP-XB", MgmtMode.SN);
        stockService.inbound(new MoveCmd(whB.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 9L, "X-IN"),
                List.of(new MoveLine(p.getId(), new BigDecimal("1"), new BigDecimal("9"), null, List.of("XSN"), 1L))));
        InvSn sn = snMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSnCode, "XSN"));

        dataFactory.roleWithPerms("R_QADJ", "SN调整", "inventory:sn:adjust", "inventory:stock:list");
        var u = dataFactory.user("qadj01", "pwd123", "R_QADJ");
        dataFactory.bindWarehouses(u.getId(), whA.getId());
        loginAs("qadj01");

        SnAdjustRequest req = new SnAdjustRequest();
        req.setStatus("OUT");
        req.setReason("越权尝试");
        assertThatThrownBy(() -> queryService.adjustSn(sn.getId(), req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(com.wille.erp.common.api.ErrorCode.NO_PERMISSION);
    }

    @Test
    void adjustSnRejectsInactiveWarehouse() {
        // 审查 I3：回库目标仓必须存在且启用（admin 也要过）
        BaseWarehouse wh = dataFactory.warehouse("QW-GH");
        BaseProduct p = dataFactory.product("QP-GH", MgmtMode.SN);
        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 10L, "GH-IN"),
                List.of(new MoveLine(p.getId(), new BigDecimal("1"), new BigDecimal("9"), null, List.of("GHSN"), 1L))));
        InvSn sn = snMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSnCode, "GHSN"));
        stockService.outbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.SALE_OUT, DocType.SAL_OUTBOUND, 11L, "GH-OUT"),
                List.of(new MoveLine(p.getId(), new BigDecimal("1"), null, null, List.of("GHSN"), 1L))));

        SnAdjustRequest req = new SnAdjustRequest();
        req.setStatus("IN_STOCK");
        req.setWarehouseId(987654321L);
        req.setReason("幽灵仓测试");
        assertThatThrownBy(() -> queryService.adjustSn(sn.getId(), req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仓库");
    }

    @Test
    void adjustSnAuditsOperator() {
        // 审查 I4：update_by/update_time 必须刷新（实体更新走审计填充）
        BaseWarehouse wh = dataFactory.warehouse("QW-AUD");
        BaseProduct p = dataFactory.product("QP-AUD", MgmtMode.SN);
        stockService.inbound(new MoveCmd(wh.getId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, 12L, "AUD-IN"),
                List.of(new MoveLine(p.getId(), new BigDecimal("1"), new BigDecimal("9"), null, List.of("AUDSN"), 1L))));
        InvSn sn = snMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSnCode, "AUDSN"));

        SnAdjustRequest req = new SnAdjustRequest();
        req.setStatus("OUT");
        req.setReason("寄样");
        queryService.adjustSn(sn.getId(), req);

        assertThat(snMapper.selectById(sn.getId()).getUpdateBy()).isEqualTo(1L);
    }

    @Test
    void stocksWarehouseFilterIntersectsScope() {
        // 审查 I1：warehouseId 参数接线且与 scope 取交集
        BaseWarehouse whA = dataFactory.warehouse("QW-FA");
        BaseWarehouse whB = dataFactory.warehouse("QW-FB");
        BaseProduct p = dataFactory.product("QP-FILTER", MgmtMode.NONE);
        seedStock(whA, p, "5", "2");
        seedStock(whB, p, "7", "2");

        StockQuery qa = new StockQuery();
        qa.setWarehouseId(whA.getId());
        assertThat(queryService.pageStocks(qa).getRecords())
                .allSatisfy(r -> assertThat(r.getWarehouseId()).isEqualTo(whA.getId()));

        StockQuery qb = new StockQuery();
        qb.setWarehouseId(999999999L);
        assertThat(queryService.pageStocks(qb).getRecords()).isEmpty();
    }

    @Test
    void flowsFilterByBizTypeAndDate() {
        BaseWarehouse wh = dataFactory.warehouse("QW-FL");
        BaseProduct p = dataFactory.product("QP-FL", MgmtMode.NONE);
        seedStock(wh, p, "10", "5");

        FlowQuery q = new FlowQuery();
        q.setBizType("PUR_IN");
        q.setStartDate(LocalDate.now().minusDays(1));
        q.setEndDate(LocalDate.now());
        var flows = queryService.pageFlows(q).getRecords();
        assertThat(flows).isNotEmpty();
        assertThat(flows).allSatisfy(f -> assertThat(f.getBizType()).isEqualTo("PUR_IN"));

        FlowQuery none = new FlowQuery();
        none.setBizType("SALE_OUT");
        assertThat(queryService.pageFlows(none).getRecords())
                .noneSatisfy(f -> assertThat(f.getBizType()).isEqualTo("SALE_OUT"));
    }
}
