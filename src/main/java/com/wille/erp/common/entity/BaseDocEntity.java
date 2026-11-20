package com.wille.erp.common.entity;

import com.wille.erp.common.enums.DocStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单据头公共业务字段（spec §2.0，11 种单据头统一）：编号/状态机/日期/币种/审核与作废留痕/财务标记。
 * 状态迁移一律走条件 UPDATE（防双击，ADR-0005）；已审核单据只作废不删除。
 */
@Getter
@Setter
public abstract class BaseDocEntity extends BaseEntity {

    private String docNo;
    private DocStatus status;
    private LocalDate docDate;
    /** 预埋恒 CNY（spec §8-14） */
    private String currency;
    private Long auditBy;
    private LocalDateTime auditTime;
    private Long voidBy;
    private LocalDateTime voidTime;
    private String voidReason;
    /** 二期财务预留：0 未生成 / 1 已生成 */
    private Integer finFlag;
}
