package com.wille.erp.sale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseDocEntity;
import lombok.Getter;
import lombok.Setter;

@TableName("sal_outbound")
@Getter
@Setter
public class SalOutbound extends BaseDocEntity {
    private Long customerId;
    private Long warehouseId;
    private Long sourceOrderId;
    private String carrier;
    private String trackingNo;
}
