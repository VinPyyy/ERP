package com.wille.erp.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 批次主档（全局，不按仓库）：UNIQUE(sku_id, lot_no)，同批号跨供应商复用（spec §2.3） */
@Getter
@Setter
@TableName("inv_lot")
public class InvLot extends BaseEntity {

    private Long skuId;
    private String lotNo;
    private LocalDate productionDate;
    /** = 生产日期 + 保质期（冗余可手改，三期临期直接查此列） */
    private LocalDate expiryDate;
    private Long supplierId;
}
