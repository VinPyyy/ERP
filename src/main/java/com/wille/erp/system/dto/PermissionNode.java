package com.wille.erp.system.dto;

import com.wille.erp.common.enums.PermType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 权限点树节点（角色授权树 / 前端菜单过滤共用） */
@Data
public class PermissionNode {

    private Long id;
    private String permCode;
    private String permName;
    private PermType type;
    private Long parentId;
    private String routePath;
    private String icon;
    private Integer sortNo;
    private List<PermissionNode> children = new ArrayList<>();
}
