package com.wille.erp.system.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * /api/auth/me 响应：用户信息 + 权限码集合 + 可见仓库（spec §5.3）。
 * warehouses 含 0 = 全部仓库；空 = 无可见仓库。
 */
@Data
@Builder
public class MeResponse {

    private Long id;
    private String username;
    private String realName;
    private List<String> roles;
    private List<String> perms;
    private List<Long> warehouses;
}
