package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 库存流水（insert-only audit trail，spec §3f）：永不 UPDATE/DELETE。
 * qty/costAmount 带符号；balance_* 为变动后主行快照（对账器 O(n) 校验链）。
 * price（业务价）与 cost_*（计价价）两线分离，二期毛利与作废冲正各取所需。
 */
@Getter
@Setter
@TableName("inv_flow")
public class InvFlow extends BaseEntity {

    private Long warehouseId;
    private Long skuId;
    private Long lotId;

    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal costPrice;
    private BigDecimal costAmount;

    private BigDecimal balanceQty;
    private BigDecimal balanceCostAmount;
    private BigDecimal balanceCostPrice;

    private BizType bizType;
    private DocType bizDocType;
    private Long bizDocId;
    private String bizDocNo;
    private Long bizDocItemId;

    /** 冲正流水指向原流水，防重复冲正 */
    private Long reverseFlowId;
}
