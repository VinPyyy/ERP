package com.wille.erp.system.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserVO {

    private Long id;
    private String username;
    private String realName;
    private String phone;
    private Integer status;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private String remark;
    private List<Long> roleIds;
    private List<String> roleNames;
    private List<Long> warehouseIds;
}
