package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 期初库存单行：批号/SN 文本落行，物化发生在审核（M3b） */
@Getter
@Setter
@TableName(value = "inv_opening_item", autoResultMap = true)
public class InvOpeningItem extends BaseEntity {

    private Long docId;
    private Integer lineNo;
    private Long skuId;
    private String skuName;
    private String spec;
    private String unit;

    private Long lotId;
    private String lotNo;
    private LocalDate productionDate;

    private BigDecimal qty;
    /** 期初均价（Excel 给定，允许 0） */
    private BigDecimal costPrice;
    private BigDecimal costAmount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> snList;
}
