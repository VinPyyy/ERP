package com.wille.erp.purchase.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@TableName(value = "pur_inbound_item", autoResultMap = true)
@Getter
@Setter
public class PurInboundItem extends BaseEntity {
    private Long docId;
    private Integer lineNo;
    private Long skuId;
    private String skuName;
    private String spec;
    private String unit;
    private Long sourceOrderItemId;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal amount;
    private Long lotId;
    private String lotNo;
    private LocalDate productionDate;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> snList;
}
