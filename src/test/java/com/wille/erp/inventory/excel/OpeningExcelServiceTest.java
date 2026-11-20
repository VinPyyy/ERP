package com.wille.erp.inventory.excel;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.inventory.dto.OpeningItemRequest;
import com.wille.erp.inventory.dto.OpeningSaveRequest;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.entity.InvOpeningItem;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvLotMapper;
import com.wille.erp.inventory.mapper.InvLotStockMapper;
import com.wille.erp.inventory.mapper.InvOpeningItemMapper;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.inventory.service.OpeningService;
import com.wille.erp.common.api.ImportResultVO;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 期初 Excel 导入（M3b 任务 2）：真实 xlsx 内存往返（EasyExcel 写→读），
 * 全量校验、有错整批不落、行号精确（对齐 M2 口径）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OpeningExcelServiceTest {

    @Autowired
    private OpeningExcelService excelService;
    @Autowired
    private OpeningService openingService;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private StockInvariantAsserter asserter;
    @Autowired
    private InvOpeningItemMapper itemMapper;
    @Autowired
    private InvStockMapper stockMapper;
    @Autowired
    private InvLotMapper lotMapper;
    @Autowired
    private InvSnMapper snMapper;
    @Autowired
    private ProductReader productReader;

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

    private static OpeningImportModel row(String sku, String qty, String price, String lotNo,
                                          String productionDate, String sns) {
        OpeningImportModel m = new OpeningImportModel();
        m.setSkuCode(sku);
        m.setQty(qty);
        m.setCostPrice(price);
        m.setLotNo(lotNo);
        m.setProductionDate(productionDate);
        m.setSnCodes(sns);
        return m;
    }

    private static byte[] toXlsx(List<OpeningImportModel> rows) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        EasyExcel.write(bos, OpeningImportModel.class).sheet("期初").doWrite(rows);
        return bos.toByteArray();
    }

    /** 建一张含占位行的草稿（导入按全量替换语义覆盖它） */
    private Long draftDoc(Long warehouseId, BaseProduct placeholder) {
        OpeningSaveRequest request = new OpeningSaveRequest();
        request.setWarehouseId(warehouseId);
        OpeningItemRequest item = new OpeningItemRequest();
        item.setSkuId(placeholder.getId());
        item.setQty(new BigDecimal("1"));
        item.setCostPrice(BigDecimal.ZERO);
        request.setItems(List.of(item));
        return openingService.create(request);
    }

    @Test
    void allValidRowsWrittenAsDraftItems() {
        BaseWarehouse wh = dataFactory.warehouse("OX-W1");
        BaseProduct plain = dataFactory.product("OX-P1", MgmtMode.NONE);
        dataFactory.product("OX-L1", MgmtMode.LOT_SN);
        Long docId = draftDoc(wh.getId(), plain);

        byte[] xlsx = toXlsx(List.of(
                row("OX-P1", "10", "5", null, null, null),
                row("OX-L1", "2", "30", "L9", "2026-01-01", "SN-A,SN-B")));

        ImportResultVO result = excelService.importToDraft(docId, new ByteArrayInputStream(xlsx));

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.getTotal()).isEqualTo(2);
        List<InvOpeningItem> items = itemMapper.selectList(Wrappers.<InvOpeningItem>lambdaQuery()
                .eq(InvOpeningItem::getDocId, docId).orderByAsc(InvOpeningItem::getLineNo));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getLineNo()).isEqualTo(1);
        assertThat(items.get(1).getLineNo()).isEqualTo(2);
        assertThat(items.get(1).getSnList()).containsExactly("SN-A", "SN-B");
        assertThat(items.get(1).getLotNo()).isEqualTo("L9");
    }

    @Test
    void anyErrorRejectsWholeFileRowReported() {
        BaseWarehouse wh = dataFactory.warehouse("OX-W2");
        BaseProduct ok = dataFactory.product("OX-OK", MgmtMode.NONE);
        dataFactory.product("OX-LOT", MgmtMode.LOT);
        dataFactory.product("OX-SN", MgmtMode.SN);
        Long docId = draftDoc(wh.getId(), ok);

        byte[] xlsx = toXlsx(List.of(
                row("OX-OK", "10", "5", null, null, null),          // 1 合法
                row("NO-SUCH", "1", "1", null, null, null),         // 2 SKU 不存在
                row("OX-LOT", "1", "1", null, null, null),          // 3 管批次缺批号
                row("OX-SN", "3", "1", null, null, "A,B"),          // 4 SN 数≠qty
                row("OX-OK", "1", "-2", null, null, null),          // 5 均价<0（且 SKU 重复）
                row("OX-LOT", "1", "1", "LA", "2026-03-01", null),  // 6 与行3同 SKU → 重复
                row("OX-LOT", "1", "1", "LB", "2026-03-02", null))); // 7 同 SKU 不同批号也重复（I6：按 SKU 判重，spec §2.3）

        ImportResultVO result = excelService.importToDraft(docId, new ByteArrayInputStream(xlsx));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(ImportResultVO.RowError::getRow)
                .containsExactlyInAnyOrder(2, 3, 4, 5, 6, 7);
        // 一行不写：占位行仍在
        assertThat(itemMapper.selectCount(Wrappers.<InvOpeningItem>lambdaQuery()
                .eq(InvOpeningItem::getDocId, docId))).isEqualTo(1);
    }

    @Test
    void importEmptyFileRejected() {
        // I5：空文件不得静默清空已有草稿行
        BaseWarehouse wh = dataFactory.warehouse("OX-W5");
        BaseProduct p = dataFactory.product("OX-P5", MgmtMode.NONE);
        Long docId = draftDoc(wh.getId(), p);

        byte[] xlsx = toXlsx(List.of());
        assertThatThrownBy(() -> excelService.importToDraft(docId, new ByteArrayInputStream(xlsx)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无数据行");
        assertThat(itemMapper.selectCount(Wrappers.<InvOpeningItem>lambdaQuery()
                .eq(InvOpeningItem::getDocId, docId))).isEqualTo(1);
    }

    @Test
    void importIntoAuditedDocRejected() {
        BaseWarehouse wh = dataFactory.warehouse("OX-W3");
        BaseProduct p = dataFactory.product("OX-P3", MgmtMode.NONE);
        OpeningSaveRequest request = new OpeningSaveRequest();
        request.setWarehouseId(wh.getId());
        OpeningItemRequest item = new OpeningItemRequest();
        item.setSkuId(p.getId());
        item.setQty(new BigDecimal("1"));
        item.setCostPrice(BigDecimal.ONE);
        request.setItems(List.of(item));
        Long docId = openingService.create(request);
        openingService.audit(docId);

        byte[] xlsx = toXlsx(List.of(row("OX-P3", "2", "2", null, null, null)));
        assertThatThrownBy(() -> excelService.importToDraft(docId, new ByteArrayInputStream(xlsx)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void importThenAuditEndToEnd() {
        BaseWarehouse wh = dataFactory.warehouse("OX-W4");
        BaseProduct p4 = dataFactory.product("OX-P4", MgmtMode.NONE);
        dataFactory.product("OX-S4", MgmtMode.SN);
        Long docId = draftDoc(wh.getId(), p4);

        byte[] xlsx = toXlsx(List.of(
                row("OX-P4", "8", "7", null, null, null),
                row("OX-S4", "1", "99", null, null, "ONLY-1")));
        excelService.importToDraft(docId, new ByteArrayInputStream(xlsx));
        openingService.audit(docId);

        InvStock s = stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, wh.getId()).eq(InvStock::getSkuId, p4.getId()));
        assertThat(s.getQty()).isEqualByComparingTo(new BigDecimal("8"));
        assertThat(s.getCostAmount()).isEqualByComparingTo(new BigDecimal("56"));

        var s4 = productReader.readByCode("OX-S4");
        InvSn sn = snMapper.selectOne(Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSkuId, s4.skuId()).eq(InvSn::getSnCode, "ONLY-1"));
        assertThat(sn.getWarehouseId()).isEqualTo(wh.getId());

        // 纯 SN 商品不物化批次
        assertThat(lotMapper.selectCount(Wrappers.<InvLot>lambdaQuery()
                .eq(InvLot::getSkuId, s4.skuId()))).isZero();
        asserter.assertConsistent();
    }
}
