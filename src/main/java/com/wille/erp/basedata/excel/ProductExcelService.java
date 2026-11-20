package com.wille.erp.basedata.excel;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.api.ImportResultVO;
import com.wille.erp.basedata.dto.ProductQuery;
import com.wille.erp.basedata.entity.BaseCategory;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.mapper.BaseCategoryMapper;
import com.wille.erp.basedata.mapper.BaseProductMapper;
import com.wille.erp.common.enums.MgmtMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 商品 Excel 导入/导出（EasyExcel 收敛点，spec §8-16：将来换 FastExcel 只动 XxxExcelService）。
 * 全量校验 → 任一行有错即整批不落库，返回精确行号错误清单；全对才插入。
 * 行号 = 数据行序（从 1 起，不含表头）。
 */
@Service
@RequiredArgsConstructor
public class ProductExcelService {

    private final BaseProductMapper productMapper;
    private final BaseCategoryMapper categoryMapper;

    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importProducts(InputStream in) {
        List<ProductImportModel> rows = EasyExcel.read(in)
                .head(ProductImportModel.class).sheet().doReadSync();

        List<ImportResultVO.RowError> errors = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            ProductImportModel row = rows.get(i);
            String error = validateRow(row, seenCodes);
            if (row.getSkuCode() != null && !row.getSkuCode().isBlank()) {
                seenCodes.add(row.getSkuCode().trim());
            }
            if (error != null) {
                errors.add(new ImportResultVO.RowError(i + 1, error));
            }
        }
        if (!errors.isEmpty()) {
            return new ImportResultVO(rows.size(), 0, errors);
        }

        for (ProductImportModel row : rows) {
            BaseProduct product = new BaseProduct();
            product.setSkuCode(row.getSkuCode().trim());
            product.setName(row.getName().trim());
            product.setSpec(blankToNull(row.getSpec()));
            product.setUnit(row.getUnit().trim());
            product.setBarcode(blankToNull(row.getBarcode()));
            MgmtMode mode = parseMgmtMode(row.getMgmtMode());
            product.setMgmtMode(mode);
            product.setShelfLifeDays(mode.lotManaged() ? row.getShelfLifeDays() : null);
            product.setExpireWarnDays(row.getExpireWarnDays());
            product.setRefSalePrice(row.getRefSalePrice() == null
                    ? null : BigDecimal.valueOf(row.getRefSalePrice()));
            if (row.getCategoryName() != null && !row.getCategoryName().isBlank()) {
                product.setCategoryId(findCategoryId(row.getCategoryName().trim()));
            }
            product.setStatus(1);
            product.setRemark(row.getRemark());
            productMapper.insert(product);
        }
        return new ImportResultVO(rows.size(), rows.size(), List.of());
    }

    public byte[] export(ProductQuery query) {
        List<BaseProduct> products = productMapper.selectList(Wrappers.<BaseProduct>lambdaQuery()
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(BaseProduct::getSkuCode, query.getKeyword())
                                .or().like(BaseProduct::getName, query.getKeyword()))
                .eq(query.getCategoryId() != null, BaseProduct::getCategoryId, query.getCategoryId())
                .eq(query.getMgmtMode() != null, BaseProduct::getMgmtMode, query.getMgmtMode())
                .eq(query.getStatus() != null, BaseProduct::getStatus, query.getStatus())
                .orderByAsc(BaseProduct::getSkuCode));

        List<ProductImportModel> models = products.stream().map(product -> {
            ProductImportModel model = new ProductImportModel();
            model.setSkuCode(product.getSkuCode());
            model.setName(product.getName());
            model.setSpec(product.getSpec());
            model.setUnit(product.getUnit());
            model.setBarcode(product.getBarcode());
            if (product.getCategoryId() != null) {
                BaseCategory category = categoryMapper.selectById(product.getCategoryId());
                model.setCategoryName(category == null ? null : category.getName());
            }
            model.setMgmtMode(product.getMgmtMode().getDesc());
            model.setShelfLifeDays(product.getShelfLifeDays());
            model.setExpireWarnDays(product.getExpireWarnDays());
            model.setRefSalePrice(product.getRefSalePrice() == null
                    ? null : product.getRefSalePrice().doubleValue());
            model.setRemark(product.getRemark());
            return model;
        }).toList();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, ProductImportModel.class).sheet("商品").doWrite(models);
        return out.toByteArray();
    }

    /** 单行校验：返回首个错误原因；null = 通过 */
    private String validateRow(ProductImportModel row, Set<String> seenCodes) {
        String skuCode = row.getSkuCode();
        if (skuCode == null || skuCode.isBlank()) {
            return "商品编码必填";
        }
        if (row.getName() == null || row.getName().isBlank()) {
            return "商品名称必填";
        }
        if (row.getUnit() == null || row.getUnit().isBlank()) {
            return "单位必填";
        }
        if (row.getMgmtMode() == null || row.getMgmtMode().isBlank()) {
            return "管理方式必填（不管/管批次/管SN/批次+SN）";
        }
        MgmtMode mode = parseMgmtMode(row.getMgmtMode());
        if (mode == null) {
            return "管理方式非法: " + row.getMgmtMode();
        }
        if (mode.lotManaged() && (row.getShelfLifeDays() == null || row.getShelfLifeDays() <= 0)) {
            return "管批次商品必须填写保质期天数";
        }
        if (row.getCategoryName() != null && !row.getCategoryName().isBlank()
                && findCategoryId(row.getCategoryName().trim()) == null) {
            List<BaseCategory> matched = categoryMapper.selectList(Wrappers.<BaseCategory>lambdaQuery()
                    .eq(BaseCategory::getName, row.getCategoryName().trim()));
            if (matched.isEmpty()) {
                return "类别不存在: " + row.getCategoryName();
            }
            return "类别名称不唯一: " + row.getCategoryName();
        }
        if (seenCodes.contains(skuCode.trim())) {
            return "文件内商品编码重复: " + skuCode;
        }
        Long exists = productMapper.selectCount(Wrappers.<BaseProduct>lambdaQuery()
                .eq(BaseProduct::getSkuCode, skuCode.trim()));
        if (exists > 0) {
            return "商品编码已存在: " + skuCode;
        }
        return null;
    }

    static MgmtMode parseMgmtMode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return switch (text.trim()) {
            case "不管", "不管控", "1" -> MgmtMode.NONE;
            case "管批次", "批次", "2" -> MgmtMode.LOT;
            case "管SN", "序列号", "3" -> MgmtMode.SN;
            case "批次+SN", "批次和SN", "4" -> MgmtMode.LOT_SN;
            default -> null;
        };
    }

    private Long findCategoryId(String name) {
        List<BaseCategory> matched = categoryMapper.selectList(Wrappers.<BaseCategory>lambdaQuery()
                .eq(BaseCategory::getName, name));
        return matched.size() == 1 ? matched.get(0).getId() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
