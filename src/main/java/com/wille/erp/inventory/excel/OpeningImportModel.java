package com.wille.erp.inventory.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 期初导入行模型（表头顺序即模板）。数值列按字符串读入、逐行校验给精确错误，
 * 避免 EasyExcel 类型转换异常吞掉行号。
 */
@Data
public class OpeningImportModel {

    @ExcelProperty("SKU编码")
    private String skuCode;

    @ExcelProperty("数量")
    private String qty;

    @ExcelProperty("期初均价")
    private String costPrice;

    @ExcelProperty("批号")
    private String lotNo;

    @ExcelProperty("生产日期")
    private String productionDate;

    @ExcelProperty("SN列表(逗号分隔)")
    private String snCodes;

    @ExcelProperty("备注")
    private String remark;
}
