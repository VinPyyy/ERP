package com.wille.erp.sale.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@TableName(value = "sal_return_item", autoResultMap = true)
@Getter
@Setter
public class SalReturnItem extends BaseEntity {
    private Long docId;
    private Integer lineNo;
    private Long skuId;
    private String skuName;
    private String spec;
    private String unit;
    private Long sourceOutboundItemId;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal amount;
    private Long lotId;
    private String lotNo;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> snList;
    private String reason;
}
