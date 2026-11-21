package com.wille.erp.sale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@TableName("sal_quote_item")
@Getter
@Setter
public class SalQuoteItem extends BaseEntity {
    private Long docId;
    private Integer lineNo;
    private Long skuId;
    private String skuName;
    private String spec;
    private String unit;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal amount;
}
