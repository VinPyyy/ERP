package com.wille.erp.purchase.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseDocEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@TableName("pur_inbound")
@Getter
@Setter
public class PurInbound extends BaseDocEntity {
    private Long supplierId;
    private Long warehouseId;
    private Long sourceOrderId;
}
