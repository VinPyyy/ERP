package com.wille.erp.basedata.excel;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.dto.ImportResultVO;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.mapper.BaseProductMapper;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.support.DataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 验收（issue 03）：真实 xlsx 程序化生成四份 fixture（全对/含错/重复编码/模式非法）；
 * 全量校验、有错不落库、精确行号；类别名解析；库内已有编码冲突检出。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductExcelImportTest {

    @Autowired
    private ProductExcelService productExcelService;
    @Autowired
    private BaseProductMapper productMapper;
    @Autowired
    private DataFactory dataFactory;

    private ByteArrayInputStream xlsx(List<ProductImportModel> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, ProductImportModel.class).sheet("商品").doWrite(rows);
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static ProductImportModel row(String code, String name, String unit, String mode) {
        ProductImportModel model = new ProductImportModel();
        model.setSkuCode(code);
        model.setName(name);
        model.setUnit(unit);
        model.setMgmtMode(mode);
        return model;
    }

    @Test
    void thousandRowsWithErrorReportsPreciseRowAndNothingInserted() {
        // 工单 03 验收：1000 行规模，错误行号精确
        List<ProductImportModel> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            rows.add(row("BULK" + i, " bulk" + i, "个", "不管"));
        }
        rows.set(499, row("BULK1", "与第1行撞车", "个", "不管")); // 第 500 行文件内重复

        ImportResultVO result = productExcelService.importProducts(xlsx(rows));

        assertThat(result.getTotal()).isEqualTo(1000);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(error -> {
            assertThat(error.getRow()).isEqualTo(500);
            assertThat(error.getReason()).contains("重复");
        });
        assertThat(productMapper.selectCount(
                Wrappers.<BaseProduct>lambdaQuery().likeRight(BaseProduct::getSkuCode, "BULK"))).isZero();
    }

    @Test
    void allValidRowsInserted() {
        ProductImportModel lotRow = row("EX002", "牛奶", "瓶", "管批次");
        lotRow.setShelfLifeDays(30);
        ImportResultVO result = productExcelService.importProducts(xlsx(List.of(
                row("EX001", "包材袋", "个", "不管"),
                lotRow)));

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(productMapper.selectCount(
                Wrappers.<BaseProduct>lambdaQuery().likeRight(BaseProduct::getSkuCode, "EX00"))).isEqualTo(2L);
    }

    @Test
    void invalidMgmtModeReportsRowNumberAndInsertsNothing() {
        ImportResultVO result = productExcelService.importProducts(xlsx(List.of(
                row("EX010", "正常", "个", "1"),
                row("EX011", "非法模式", "个", "抽签"))));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(error -> {
            assertThat(error.getRow()).isEqualTo(2);
            assertThat(error.getReason()).contains("管理方式");
        });
        // 有错不落库
        assertThat(productMapper.selectCount(
                Wrappers.<BaseProduct>lambdaQuery().eq(BaseProduct::getSkuCode, "EX010"))).isZero();
    }

    @Test
    void duplicateCodeWithinFileReported() {
        ImportResultVO result = productExcelService.importProducts(xlsx(List.of(
                row("EX020", "第一次", "个", "不管"),
                row("EX020", "第二次", "个", "不管"))));

        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.getReason()).contains("重复"));
        assertThat(productMapper.selectCount(
                Wrappers.<BaseProduct>lambdaQuery().eq(BaseProduct::getSkuCode, "EX020"))).isZero();
    }

    @Test
    void existingCodeInDbReported() {
        dataFactory.product("EX030", MgmtMode.NONE);

        ImportResultVO result = productExcelService.importProducts(xlsx(List.of(
                row("EX030", "撞库编码", "个", "不管"))));

        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.getReason()).contains("已存在"));
    }

    @Test
    void missingRequiredFieldsReported() {
        ImportResultVO result = productExcelService.importProducts(xlsx(List.of(
                row("EX040", "", "个", "不管"),
                row("", "缺编码", "个", "不管"))));

        assertThat(result.getErrors()).hasSize(2);
    }

    @Test
    void lotModeWithoutShelfLifeReported() {
        ProductImportModel lotRow = row("EX050", "奶粉", "罐", "批次+SN");
        // 不填 shelfLifeDays
        ImportResultVO result = productExcelService.importProducts(xlsx(List.of(lotRow)));

        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.getReason()).contains("保质期"));
    }

    @Test
    void categoryNameResolvedToId() {
        dataFactory.category("乳品");
        ProductImportModel withCategory = row("EX060", "酸奶", "盒", "管批次");
        withCategory.setCategoryName("乳品");
        withCategory.setShelfLifeDays(30);
        ProductImportModel unknownCategory = row("EX061", "奶酪", "块", "不管");
        unknownCategory.setCategoryName("不存在的类别");

        ImportResultVO result = productExcelService.importProducts(xlsx(List.of(withCategory, unknownCategory)));

        assertThat(result.getErrors()).anySatisfy(error -> {
            assertThat(error.getRow()).isEqualTo(2);
            assertThat(error.getReason()).contains("类别");
        });
        assertThat(productMapper.selectCount(
                Wrappers.<BaseProduct>lambdaQuery().eq(BaseProduct::getSkuCode, "EX060"))).isZero();
    }
}
