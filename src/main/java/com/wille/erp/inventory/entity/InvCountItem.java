package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@TableName("inv_count_item")
@Getter
@Setter
public class InvCountItem extends BaseEntity {
    private Long docId;
    private Integer lineNo;
    private Long skuId;
    private String skuName;
    private String spec;
    private String unit;
    private Long lotId;
    private BigDecimal bookQty;
    private BigDecimal countQty;
    private BigDecimal diffQty;
}
