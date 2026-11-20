import * as forge from 'node-forge'
import http from './http'

export interface CryptoChallenge {
  publicKey: string
  nonce: string
  expiresIn: number
}

export interface EncryptedCredential {
  encryptedPassword: string
  nonce: string
  ts: number
}

/** 与后端 ErrorCode.LOGIN_CHALLENGE_INVALID 对齐 */
export const LOGIN_CHALLENGE_INVALID = 10006

/**
 * 领取一次性挑战，RSA-OAEP(SHA-256/MGF1-SHA1) 加密 "nonce.密码"。
 * 登录与重置密码共用；选 node-forge 而非 WebCrypto：crypto.subtle 仅在
 * 安全上下文可用，内网 HTTP 部署会直接不可用；forge 为纯 JS，环境无关。
 * 模块被登录页与用户管理页引用，forge 随这两个懒加载 chunk 走，不进主包。
 */
export async function encryptPassword(password: string): Promise<EncryptedCredential> {
  const challenge = (await http.get('/auth/crypto')) as unknown as CryptoChallenge
  const publicKey = forge.pki.publicKeyFromPem(challenge.publicKey)
  // node-forge 1.x 三参签名：scheme 字符串 + options（md=SHA-256，mgf1=SHA-1，对齐 Java OAEPWithSHA-256AndMGF1Padding）
  const encrypted = publicKey.encrypt(`${challenge.nonce}.${password}`, 'RSA-OAEP', {
    md: forge.md.sha256.create(),
    mgf1: { md: forge.md.sha1.create() },
  })
  return {
    encryptedPassword: forge.util.encode64(encrypted),
    nonce: challenge.nonce,
    ts: Date.now(),
  }
}
