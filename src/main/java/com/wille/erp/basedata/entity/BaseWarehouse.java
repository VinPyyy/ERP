package com.wille.erp.basedata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 仓库（≤10 个，数据权限单位，GLOSSARY"仓库"）。
 */
@Getter
@Setter
@TableName("base_warehouse")
public class BaseWarehouse extends BaseEntity {

    private String code;
    private String name;
    private String address;
    /** 负责人 */
    private String keeper;
    private Integer status;
    private Integer sortNo;
}
