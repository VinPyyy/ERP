package com.wille.erp.system.service;

import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.system.dto.CryptoChallenge;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录密码传输加密：RSA-OAEP 往返、nonce 一次性、时间戳窗口、密文与 nonce 绑定。
 */
class LoginCryptoServiceTest {

    private final LoginCryptoService service = new LoginCryptoService();

    /** 与前端 node-forge / 浏览器 WebCrypto 相同的参数：OAEP SHA-256 + MGF1-SHA1（Java 默认） */
    private static String encrypt(String publicKeyPem, String payload) throws Exception {
        String b64 = publicKeyPem
                .replaceAll("-----\\w+ PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        PublicKey pk = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(b64)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pk);
        return Base64.getEncoder().encodeToString(cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertChallengeInvalid(Throwable t) {
        assertThat(t).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.LOGIN_CHALLENGE_INVALID);
    }

    @Test
    void roundTripRecoversPassword() throws Exception {
        CryptoChallenge ch = service.issue();
        String cipher = encrypt(ch.publicKey(), ch.nonce() + ".S3cr3t#pwd");

        assertThat(service.verifyAndDecrypt(ch.nonce(), System.currentTimeMillis(), cipher))
                .isEqualTo("S3cr3t#pwd");
    }

    @Test
    void issuedPublicKeyIsPemSpki() {
        assertThat(service.issue().publicKey())
                .startsWith("-----BEGIN PUBLIC KEY-----")
                .endsWith("-----END PUBLIC KEY-----");
    }

    @Test
    void nonceCannotBeReused() throws Exception {
        CryptoChallenge ch = service.issue();
        String cipher = encrypt(ch.publicKey(), ch.nonce() + ".pwd");
        service.verifyAndDecrypt(ch.nonce(), System.currentTimeMillis(), cipher);

        assertThatThrownBy(() -> service.verifyAndDecrypt(ch.nonce(), System.currentTimeMillis(), cipher))
                .satisfies(LoginCryptoServiceTest::assertChallengeInvalid);
    }

    @Test
    void unknownNonceRejected() throws Exception {
        CryptoChallenge ch = service.issue();
        String cipher = encrypt(ch.publicKey(), "deadbeef" + ".pwd");

        assertThatThrownBy(() -> service.verifyAndDecrypt("deadbeef", System.currentTimeMillis(), cipher))
                .satisfies(LoginCryptoServiceTest::assertChallengeInvalid);
    }

    @Test
    void staleTimestampRejected() throws Exception {
        CryptoChallenge ch = service.issue();
        String cipher = encrypt(ch.publicKey(), ch.nonce() + ".pwd");
        long stale = System.currentTimeMillis() - 10 * 60 * 1000L;

        assertThatThrownBy(() -> service.verifyAndDecrypt(ch.nonce(), stale, cipher))
                .satisfies(LoginCryptoServiceTest::assertChallengeInvalid);
    }

    @Test
    void ciphertextBoundToAnotherNonceRejected() throws Exception {
        CryptoChallenge ch = service.issue();
        // 攻击者拿旧密文配新 nonce：密文内嵌 nonce 与请求 nonce 不一致
        String staleCipher = encrypt(ch.publicKey(), "old-nonce" + ".pwd");
        CryptoChallenge fresh = service.issue();

        assertThatThrownBy(() -> service.verifyAndDecrypt(fresh.nonce(), System.currentTimeMillis(), staleCipher))
                .satisfies(LoginCryptoServiceTest::assertChallengeInvalid);
    }

    @Test
    void garbageCiphertextRejected() {
        CryptoChallenge ch = service.issue();

        assertThatThrownBy(() -> service.verifyAndDecrypt(
                ch.nonce(), System.currentTimeMillis(), Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))
                .satisfies(LoginCryptoServiceTest::assertChallengeInvalid);
    }
}
