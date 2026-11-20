package com.wille.erp.system.dto;

/**
 * 登录加密挑战（spec §5.3 扩展）：前端用 publicKey 做 RSA-OAEP 加密密码，
 * 密文内嵌 nonce；nonce 一次性、TTL 内有效，防重放。
 */
public record CryptoChallenge(String publicKey, String nonce, int expiresIn) {
}
