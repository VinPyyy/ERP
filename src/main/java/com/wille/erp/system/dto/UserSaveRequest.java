package com.wille.erp.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 用户新建/编辑请求。密码仅 create 使用且不明文传输：
 * encryptedPassword = Base64( RSA-OAEP( "nonce.初始密码" ) )，update 忽略三个密码字段。
 */
@Data
public class UserSaveRequest {

    @NotBlank
    private String username;

    /** create 必填（密文）；update 忽略 */
    private String encryptedPassword;

    private String nonce;

    private Long ts;

    private String realName;
    private String phone;
    /** 1 启用 / 0 停用 */
    private Integer status;
    private String remark;
    private List<Long> roleIds;
    private List<Long> warehouseIds;
}
