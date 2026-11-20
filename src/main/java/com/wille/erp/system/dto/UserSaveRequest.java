package com.wille.erp.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class UserSaveRequest {

    @NotBlank
    private String username;

    /** create 必填；update 忽略（改密走 resetPassword） */
    private String password;

    private String realName;
    private String phone;
    /** 1 启用 / 0 停用 */
    private Integer status;
    private String remark;
    private List<Long> roleIds;
    /** 可见仓库；含 0 = 全部仓库 */
    private List<Long> warehouseIds;
}
