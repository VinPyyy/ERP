package com.wille.erp.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import com.wille.erp.common.enums.ConfigValueType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sys_config")
public class SysConfig extends BaseEntity {

    private String paramKey;
    private String paramName;
    private String paramValue;
    private ConfigValueType valueType;
}
