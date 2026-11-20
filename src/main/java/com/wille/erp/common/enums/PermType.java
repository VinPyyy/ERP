package com.wille.erp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 权限点类型（sys_permission.type）：菜单 + 按钮合一（spec §2.1）。
 */
@Getter
@AllArgsConstructor
public enum PermType {

    MENU("菜单"),
    BUTTON("按钮");

    private final String desc;
}
