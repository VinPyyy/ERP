package com.wille.erp.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import com.wille.erp.common.enums.DocType;
import lombok.Getter;
import lombok.Setter;

/**
 * 单据编号规则：{prefix}-{datePattern}-{seqLength 位流水}，如 XSDD-20261119-0001。
 * 取号 = 单事务内该行 SELECT FOR UPDATE + current_seq 自增 + 期间变更重置；作废不回收（跳号正常）。
 */
@Getter
@Setter
@TableName("sys_doc_no_rule")
public class SysDocNoRule extends BaseEntity {

    private DocType docType;
    private String prefix;
    /** yyyyMMdd / yyyyMM / 空 */
    private String datePattern;
    private Integer seqLength;
    /** DAY / MONTH / NONE */
    private String seqScope;
    private Long currentSeq;
    /** 当前流水所属期间（如 20261119），跨期重置 current_seq */
    private String currentPeriod;
}
