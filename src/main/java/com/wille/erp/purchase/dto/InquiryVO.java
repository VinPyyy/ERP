package com.wille.erp.purchase.dto;

import com.wille.erp.purchase.entity.PurInquiry;
import com.wille.erp.purchase.entity.PurInquiryItem;
import lombok.Data;

import java.util.List;

/**
 * 询价详情（M4 审查 C2）：统一 {doc,items} 形态 + 枚举名状态，与订单/入库/退货 VO 对齐，
 * 前端按同一 DocVO 契约消费。
 */
@Data
public class InquiryVO {
    private PurInquiry doc;
    private List<PurInquiryItem> items;
}
