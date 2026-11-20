package com.wille.erp.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 登录请求：密码不在网络上明文传输。
 * encryptedPassword = Base64( RSA-OAEP( "nonce.明文密码" ) )，nonce/ts 与 /auth/crypto 签发值一致。
 */
@Data
public class LoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String encryptedPassword;

    @NotBlank
    private String nonce;

    @NotNull
    private Long ts;
}
