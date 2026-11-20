package com.wille.erp.purchase.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseDocEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@TableName("pur_return")
@Getter
@Setter
public class PurReturn extends BaseDocEntity {
    private Long supplierId;
    private Long warehouseId;
    private Long sourceInboundId;
}
