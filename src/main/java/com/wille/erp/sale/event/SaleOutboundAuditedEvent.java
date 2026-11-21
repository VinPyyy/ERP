package com.wille.erp.sale.event;

import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.event.DocEvent;

/** 销售出库审核/作废事件：二期应收/红冲钩子（spec §4.1-8/§4.2-8）；一期发布无监听。 */
public class SaleOutboundAuditedEvent extends DocEvent {

    public SaleOutboundAuditedEvent(Long docId, String docNo, Action action) {
        super(DocType.SAL_OUTBOUND, docId, docNo, action);
    }
}
