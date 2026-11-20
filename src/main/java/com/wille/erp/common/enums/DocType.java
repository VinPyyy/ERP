package com.wille.erp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 11 种单据类型（GLOSSARY"单据"）。
 * 多态引用列（biz_doc_type 等）以 VARCHAR(30) 存枚举名（spec §2.0），故用 name() 而非 code。
 */
@Getter
@AllArgsConstructor
public enum DocType {

    PUR_INQUIRY("CGXJ", "询价单"),
    PUR_ORDER("CGDD", "采购订单"),
    PUR_INBOUND("CGRK", "采购入库单"),
    PUR_RETURN("CGTH", "采购退货单"),
    SAL_QUOTE("XSBJ", "销售报价单"),
    SAL_ORDER("XSDD", "销售订单"),
    SAL_OUTBOUND("XSCK", "销售出库单"),
    SAL_RETURN("XSTH", "销售退货单"),
    TRANSFER("DB", "调拨单"),
    COUNT("PD", "盘点单"),
    OPENING("QC", "期初单");

    /** 单据编号前缀（sys_doc_no_rule.prefix 种子与之一致） */
    private final String prefix;
    private final String desc;
}
