package com.wille.erp.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wille.erp.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;
    /** BCrypt hash，任何接口不得回传 */
    private String password;
    private String realName;
    private String phone;
    /** 1 启用 / 0 停用 */
    private Integer status;
    private LocalDateTime lastLoginTime;
}
