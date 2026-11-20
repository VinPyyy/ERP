package com.wille.erp.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sys_role")
public class SysRole extends BaseEntity {

    private String roleCode;
    private String roleName;
    /** 1 = 预置角色（采购员/销售员/仓管/财务/管理员），不可删 */
    private Integer builtIn;
}
