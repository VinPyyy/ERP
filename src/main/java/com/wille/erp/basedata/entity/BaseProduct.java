package com.wille.erp.basedata.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import com.wille.erp.common.enums.MgmtMode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 商品（一行 = 一个 SKU，GLOSSARY"商品（SKU）"）。
 * mgmt_mode 被库存/流水/单据引用后禁改（spec §8-15，经 ProductReferenceCheckEvent 校验）。
 */
@Getter
@Setter
@TableName("base_product")
public class BaseProduct extends BaseEntity {

    private String skuCode;
    private String name;

    /** 可清空字段：updateById 需写 null（FieldStrategy.ALWAYS） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String spec;

    /** 单一计量单位（不做多单位换算） */
    private String unit;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String barcode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long categoryId;

    private MgmtMode mgmtMode;

    /** 保质期天数：管批次模式必填 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer shelfLifeDays;

    /** 临期预警提前天数：一期预埋只存不算（spec §8-4） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer expireWarnDays;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal refSalePrice;

    /** 1 启用 / 0 停用 */
    private Integer status;
}
