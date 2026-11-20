package com.wille.erp.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import com.wille.erp.common.enums.PermType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sys_permission")
public class SysPermission extends BaseEntity {

    /** 如 purchase:order:audit / inventory:cost:view */
    private String permCode;
    private String permName;
    private PermType type;
    /** 树结构，0 = 根 */
    private Long parentId;
    /** MENU 用：前端静态路由路径 */
    private String routePath;
    private String icon;
    private Integer sortNo;
}
