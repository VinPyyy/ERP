package com.wille.erp.sale.event;

import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.event.DocEvent;

/** 销售订单审核/作废事件（spec §4.2-8 二期钩子预留；一期发布无监听）。 */
public class SaleOrderAuditedEvent extends DocEvent {

    public SaleOrderAuditedEvent(Long docId, String docNo, Action action) {
        super(DocType.SAL_ORDER, docId, docNo, action);
    }
}
