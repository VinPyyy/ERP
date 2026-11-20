package com.wille.erp.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 重置密码请求：新密码不在网络上明文传输。
 * encryptedPassword = Base64( RSA-OAEP( "nonce.新密码" ) )，挑战与 /auth/crypto 同源。
 */
@Data
public class ResetPasswordRequest {

    @NotBlank
    private String encryptedPassword;

    @NotBlank
    private String nonce;

    @NotNull
    private Long ts;
}
