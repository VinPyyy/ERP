package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 库存主行（计价 + 可用量权威），仓库×SKU（ADR-0001/0002）。
 * 可用量 = qty − lockedQty（派生不落库）；并发走 FOR UPDATE（ADR-0005）。
 */
@Getter
@Setter
@TableName("inv_stock")
public class InvStock extends BaseEntity {

    private Long warehouseId;
    private Long skuId;
    private BigDecimal qty;
    private BigDecimal lockedQty;
    private BigDecimal costPrice;
    private BigDecimal costAmount;
}
