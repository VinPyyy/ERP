package com.wille.erp.common.event;

import com.wille.erp.common.enums.DocType;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 单据事件基类：Spring 进程内事件（无 MQ，spec §1）。
 * 一期只发布、无监听者；二期财务模块以 @TransactionalEventListener 挂接
 * （权责发生制：出库审核→应收、入库审核→应付）。
 */
@Getter
public abstract class DocEvent {

    /** 事件语义：审核通过 / 作废 */
    public enum Action { AUDITED, VOIDED }

    private final DocType docType;
    private final Long docId;
    private final String docNo;
    private final Action action;
    private final LocalDateTime occurredAt;

    protected DocEvent(DocType docType, Long docId, String docNo, Action action) {
        this.docType = docType;
        this.docId = docId;
        this.docNo = docNo;
        this.action = action;
        this.occurredAt = LocalDateTime.now();
    }
}
