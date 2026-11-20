package com.wille.erp.purchase.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseDocEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@TableName("pur_inquiry")
@Getter
@Setter
public class PurInquiry extends BaseDocEntity {
    private String title;
}
