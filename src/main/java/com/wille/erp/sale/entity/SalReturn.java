package com.wille.erp.sale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseDocEntity;
import lombok.Getter;
import lombok.Setter;

@TableName("sal_return")
@Getter
@Setter
public class SalReturn extends BaseDocEntity {
    private Long customerId;
    private Long warehouseId;
    private Long sourceOutboundId;
}
