package com.wille.erp.inventory.excel;

import com.alibaba.excel.EasyExcel;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ImportResultVO;
import com.wille.erp.inventory.dto.OpeningItemRequest;
import com.wille.erp.inventory.service.OpeningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 期初 Excel 导入（M3b 任务 2，口径对齐 M2 ProductExcelService）：
 * 全量校验 → 任一行有错整批不落库、返回行号级错误清单；全对则整体替换草稿行。
 * 批号/SN 只存文本，物化发生在审核。行号 = 数据行序（从 1 起，不含表头）。
 */
@Service
@RequiredArgsConstructor
public class OpeningExcelService {

    private final ProductReader productReader;
    private final OpeningService openingService;

    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importToDraft(Long docId, InputStream in) {
        List<OpeningImportModel> rows = EasyExcel.read(in)
                .head(OpeningImportModel.class).sheet().doReadSync();
        if (rows.isEmpty()) {
            // REPLACE 语义下空文件会清空既有草稿行，必须显式拒绝（审查 I5）
            throw new com.wille.erp.common.exception.BizException(
                    com.wille.erp.common.api.ErrorCode.PARAM_INVALID, "导入文件无数据行");
        }

        List<ImportResultVO.RowError> errors = new ArrayList<>();
        List<OpeningItemRequest> items = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            OpeningImportModel row = rows.get(i);
            String error = validateRow(row, seenKeys, items);
            if (error != null) {
                errors.add(new ImportResultVO.RowError(i + 1, error));
            }
        }
        if (!errors.isEmpty()) {
            return new ImportResultVO(rows.size(), 0, errors);
        }
        openingService.importItems(docId, items);
        return new ImportResultVO(rows.size(), items.size(), List.of());
    }

    /** 模板下载：仅表头 */
    public byte[] template() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        EasyExcel.write(bos, OpeningImportModel.class).sheet("期初").doWrite(List.of());
        return bos.toByteArray();
    }

    private String validateRow(OpeningImportModel row, Set<String> seenKeys, List<OpeningItemRequest> items) {
        if (row.getSkuCode() == null || row.getSkuCode().isBlank()) {
            return "SKU编码不能为空";
        }
        ProductReader.ProductInfo product = productReader.readByCode(row.getSkuCode().trim());
        if (product == null) {
            return "SKU编码不存在: " + row.getSkuCode().trim();
        }
        BigDecimal qty = parseDecimal(row.getQty());
        if (qty == null || qty.signum() <= 0) {
            return "数量必须为正数";
        }
        if (qty.stripTrailingZeros().scale() > 0 && product.snManaged()) {
            return "管SN商品数量必须为整数";
        }
        BigDecimal price = parseDecimal(row.getCostPrice());
        String lotNo = row.getLotNo() == null || row.getLotNo().isBlank() ? null : row.getLotNo().trim();
        // 判重键 = skuId（spec §2.3 同 wh×SKU 仅一张非作废期初行；审查 I6 裁决两层同口径）
        if (!seenKeys.add(String.valueOf(product.skuId()))) {
            return "同单内 SKU 重复";
        }
        if (product.lotManaged() && lotNo == null) {
            return "管批次商品必须填批号";
        }
        if (price == null || price.signum() < 0) {
            return "期初均价必须为非负数";
        }
        List<String> snList = splitSns(row.getSnCodes());
        if (product.snManaged() && snList.size() != qty.intValueExact()) {
            return "SN数量必须与数量一致";
        }
        if (!product.snManaged() && !snList.isEmpty()) {
            return "该商品不管理SN";
        }
        LocalDate productionDate = null;
        if (row.getProductionDate() != null && !row.getProductionDate().isBlank()) {
            try {
                productionDate = LocalDate.parse(row.getProductionDate().trim());
            } catch (DateTimeParseException e) {
                return "生产日期格式应为 yyyy-MM-dd";
            }
        }
        if (product.lotManaged() && productionDate == null) {
            return "管批次商品必须填生产日期";
        }

        OpeningItemRequest item = new OpeningItemRequest();
        item.setSkuId(product.skuId());
        item.setQty(qty);
        item.setCostPrice(price);
        item.setLotNo(lotNo);
        item.setProductionDate(productionDate);
        item.setSnList(snList.isEmpty() ? null : snList);
        item.setRemark(row.getRemark());
        items.add(item);
        return null;
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> splitSns(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("[,，]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
