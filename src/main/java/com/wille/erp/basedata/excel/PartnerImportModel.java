package com.wille.erp.basedata.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/** 客户/供应商共用导入导出行模型 */
@Data
public class PartnerImportModel {

    @ExcelProperty("编码")
    private String code;

    @ExcelProperty("名称")
    private String name;

    @ExcelProperty("联系人")
    private String contact;

    @ExcelProperty("电话")
    private String phone;

    @ExcelProperty("地址")
    private String address;

    @ExcelProperty("税号")
    private String taxNo;

    @ExcelProperty("备注")
    private String remark;
}
