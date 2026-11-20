package com.wille.erp.basedata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("base_customer")
public class BaseCustomer extends BaseEntity {

    private String code;
    private String name;
    private String contact;
    private String phone;
    private String address;
    /** 税号（二期发票登记预埋） */
    private String taxNo;
    private Integer status;
}
