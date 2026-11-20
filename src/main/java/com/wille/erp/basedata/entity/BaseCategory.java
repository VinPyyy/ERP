package com.wille.erp.basedata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("base_category")
public class BaseCategory extends BaseEntity {

    private String name;
    /** 0 = 根 */
    private Long parentId;
    private Integer sortNo;
}
