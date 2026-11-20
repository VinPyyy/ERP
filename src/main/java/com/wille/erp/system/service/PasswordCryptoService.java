package com.wille.erp.system.service;

import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.system.dto.CryptoChallenge;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密码传输加密（登录 / 重置密码共用）：RSA-2048 密钥对进程内存生成
 * （重启即轮换，前端每次现取公钥）。密文格式 OAEP(SHA-256/MGF1-SHA1) 加密
 * "nonce.password"，把密文与一次性 nonce 绑定，被抓包也无法配新 nonce 重放。
 */
@Service
public class PasswordCryptoService {

    public static final int NONCE_TTL_SECONDS = 120;
    private static final String TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    private final KeyPair keyPair;
    private final SecureRandom random = new SecureRandom();
    /** nonce → 过期毫秒；remove() 的原子性即"一次性"语义 */
    private final ConcurrentHashMap<String, Long> issued = new ConcurrentHashMap<>();

    public PasswordCryptoService() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, random);
            this.keyPair = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 RSA 支持", e);
        }
    }

    /** 签发一次性挑战：公钥（PEM SPKI）+ nonce + TTL */
    public CryptoChallenge issue() {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        long now = System.currentTimeMillis();
        issued.values().removeIf(expiresAt -> expiresAt < now);
        issued.put(nonce, now + NONCE_TTL_SECONDS * 1000L);
        return new CryptoChallenge(publicKeyPem(), nonce, NONCE_TTL_SECONDS);
    }

    /**
     * 校验并解密：时间戳窗口 → nonce 一次性 → OAEP 解密 → 内嵌 nonce 一致。
     * 任一环节失败统一 LOGIN_CHALLENGE_INVALID（10006），前端应重取挑战重试。
     */
    public String verifyAndDecrypt(String nonce, long ts, String ciphertextB64) {
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > NONCE_TTL_SECONDS * 1000L) {
            throw new BizException(ErrorCode.LOGIN_CHALLENGE_INVALID, "登录请求时间偏差过大，请校准设备时间后重试");
        }
        Long expiresAt = issued.remove(nonce);
        if (expiresAt == null || expiresAt < now) {
            throw new BizException(ErrorCode.LOGIN_CHALLENGE_INVALID, "登录凭证已过期，请重试");
        }
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
            plain = cipher.doFinal(Base64.getMimeDecoder().decode(ciphertextB64));
        } catch (Exception e) {
            throw new BizException(ErrorCode.LOGIN_CHALLENGE_INVALID, "登录凭证无效，请重试");
        }
        String text = new String(plain, StandardCharsets.UTF_8);
        int sep = text.indexOf('.');
        if (sep <= 0 || !nonce.equals(text.substring(0, sep))) {
            throw new BizException(ErrorCode.LOGIN_CHALLENGE_INVALID, "登录凭证无效，请重试");
        }
        return text.substring(sep + 1);
    }

    private String publicKeyPem() {
        String b64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        StringBuilder sb = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        return sb.append("-----END PUBLIC KEY-----").toString();
    }
}
