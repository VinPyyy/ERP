package com.wille.erp.basedata.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 商品导入/导出行模型（表头顺序即模板）。管理方式接受中文（不管/管批次/管SN/批次+SN）或数字 1-4。
 */
@Data
public class ProductImportModel {

    @ExcelProperty("商品编码")
    private String skuCode;

    @ExcelProperty("商品名称")
    private String name;

    @ExcelProperty("规格")
    private String spec;

    @ExcelProperty("单位")
    private String unit;

    @ExcelProperty("条码")
    private String barcode;

    @ExcelProperty("类别")
    private String categoryName;

    @ExcelProperty("管理方式")
    private String mgmtMode;

    @ExcelProperty("保质期天数")
    private Integer shelfLifeDays;

    @ExcelProperty("临期预警天数")
    private Integer expireWarnDays;

    @ExcelProperty("参考售价")
    private Double refSalePrice;

    @ExcelProperty("备注")
    private String remark;
}
