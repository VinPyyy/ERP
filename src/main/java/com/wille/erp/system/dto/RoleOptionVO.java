package com.wille.erp.system.dto;

/**
 * 角色下拉引用数据（docs/system/reference-options.md）：仅 id+编码+名称，
 * 不含备注/审计字段；登录即可访问。
 */
public record RoleOptionVO(Long id, String roleCode, String roleName) {
}
