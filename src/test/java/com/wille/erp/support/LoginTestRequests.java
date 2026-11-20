package com.wille.erp.support;

import com.wille.erp.system.dto.CryptoChallenge;
import com.wille.erp.system.dto.LoginRequest;
import com.wille.erp.system.dto.ResetPasswordRequest;
import com.wille.erp.system.service.PasswordCryptoService;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 测试侧构造加密登录请求，模拟前端 node-forge 行为（OAEP SHA-256/MGF1-SHA1）。
 */
public final class LoginTestRequests {

    private LoginTestRequests() {
    }

    public static LoginRequest encrypted(PasswordCryptoService crypto, String username, String password) {
        CryptoChallenge ch = crypto.issue();
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setEncryptedPassword(encrypt(ch.publicKey(), ch.nonce() + "." + password));
        request.setNonce(ch.nonce());
        request.setTs(System.currentTimeMillis());
        return request;
    }

    /** 重置密码请求：与登录同一挑战机制 */
    public static ResetPasswordRequest encryptedReset(PasswordCryptoService crypto, String newPassword) {
        CryptoChallenge ch = crypto.issue();
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setEncryptedPassword(encrypt(ch.publicKey(), ch.nonce() + "." + newPassword));
        request.setNonce(ch.nonce());
        request.setTs(System.currentTimeMillis());
        return request;
    }

    public static String encrypt(String publicKeyPem, String payload) {
        try {
            String b64 = publicKeyPem
                    .replaceAll("-----\\w+ PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            PublicKey pk = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(b64)));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, pk);
            return Base64.getEncoder().encodeToString(cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
