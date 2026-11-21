package com.wille.erp.sale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseDocEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@TableName("sal_quote")
@Getter
@Setter
public class SalQuote extends BaseDocEntity {
    private Long customerId;
    private LocalDate validUntil;
}
