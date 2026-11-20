# 密码传输加密方案（实现文档）

> 决策记录见 [ADR 0006](../adr/0006-password-transport-rsa-oaep-challenge.md)。
> 本文面向实现与维护者：接口契约、密码学参数、校验顺序、前后端代码位置、验证记录与已知边界。
> 状态：已上线于 `m0-scaffold`（commit `f2ab596` 前后），覆盖登录、重置密码、新建用户三个入口。

## 1. 威胁模型

| 威胁 | 本方案是否防住 | 手段 |
|---|---|---|
| HTTP 链路上密码被被动窃听 | ✅ | RSA-OAEP 字段加密，网络上只出现密文 |
| 服务端/网关请求日志落明文密码 | ✅ | 日志里只有 `encryptedPassword`（每次密文不同，OAEP 随机化） |
| 抓包重放整个登录请求 | ✅ | nonce 一次性 + 时间戳窗口 |
| 抓旧密文配新 nonce 重放（pass-the-ciphertext） | ✅ | 密文内嵌 nonce，服务端比对绑定 |
| 在线暴力破解密码 | ✅ | 用户名维度限流：5 次失败锁 15 分钟 |
| 主动中间人替换 `/auth/crypto` 返回的公钥 | ❌ | 需 TLS/公钥固定，见 §9 |
| 服务端被攻破后的存量密码 | ➖ | 由 BCrypt 存储负责（本方案不涉及，存储层未改动） |

## 2. 时序总览

```
前端                                    后端
 │  ① GET /api/auth/crypto              │
 │ ────────────────────────────────────> │ 生成 nonce(16B 随机)，记录过期时间 now+120s
 │  { publicKey(PEM), nonce, expiresIn } │ 返回进程内存生成的 RSA-2048 公钥
 │ <──────────────────────────────────── │
 │  ② 加密 payload = "nonce.明文密码"     │
 │     ciphertext = RSA-OAEP(pubkey)     │
 │  ③ POST /api/auth/login               │
 │  { username, encryptedPassword,       │
 │    nonce, ts }                        │
 │ ────────────────────────────────────> │ assertNotLocked(username)
 │                                       │ 时间戳窗口 → nonce 一次性 → 解密 → 内嵌 nonce 一致
 │                                       │ BCrypt matches → 签发 Sa-Token
```

重置密码（`PUT /api/system/users/{id}/password`）与新建用户（`POST /api/system/users`）复用同一挑战接口与同一校验函数，只是密文里包的是**新密码**。

## 3. 接口契约

### 3.1 `GET /api/auth/crypto`（白名单，无需登录）

```json
{ "code": 0, "data": {
    "publicKey": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----",
    "nonce": "URL-safe Base64，16 字节随机数",
    "expiresIn": 120
}}
```

- 每次调用签发新 nonce；旧 nonce 不因新签发而失效（各自独立计时）。
- 公钥为 SPKI PEM，Base64 每 64 字符换行。

### 3.2 携带密码的三个接口

| 接口 | 权限 | 密码字段 |
|---|---|---|
| `POST /api/auth/login` | 白名单 | `encryptedPassword` + `nonce` + `ts` |
| `PUT /api/system/users/{id}/password` | `system:user:reset-password` | 同上 |
| `POST /api/system/users`（新建） | `system:user:create` | 同上；`update` 忽略这三个字段 |

请求示例（登录）：

```json
{ "username": "admin",
  "encryptedPassword": "Base64(RSA-OAEP(\"<nonce>.<明文密码>\"))",
  "nonce": "<与领取一致>",
  "ts": 1763600000000 }
```

- `encryptedPassword`/`nonce` 必填（`@NotBlank`），`ts` 必填（`@NotNull`，毫秒时间戳）——缺失直接 `10001` 参数错误，**旧版明文 `password` 字段请求会被整体拒绝**。
- 密码长度 ≥6 的校验在**服务端解密之后**执行（对密文做长度校验无意义）。

### 3.3 错误码（`ErrorCode`）

| 码 | 含义 | 前端行为 |
|---|---|---|
| 10001 | 参数缺失 / 解密后长度不足 / 用户名占用 | 表单内展示 |
| 10004 | 用户名或密码错误（不泄露用户是否存在） | 计入限流次数 |
| 10006 | 挑战过期/重放/密文与 nonce 不匹配 | 登录页**自动重取挑战静默重试一次**；其余入口提示重试 |
| 10007 | 失败次数过多被锁定（含剩余分钟数） | 不计入也不触发解密后的密码校验 |

## 4. 密码学参数

| 项 | 取值 | 说明 |
|---|---|---|
| 密钥 | RSA-2048 | 进程启动时生成，**内存态，不落盘**；重启轮换（前端每次现取公钥，无兼容问题） |
| 填充 | `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` | 主摘要 SHA-256，MGF1 摘要 SHA-1（Java 该变换的默认组合） |
| 明文 payload | `nonce + "." + password`（UTF-8） | 以第一个 `.` 分割，密码可含 `.` |
| nonce | `SecureRandom` 16 字节 → URL-safe Base64（无填充） | 一次性：`ConcurrentHashMap.remove()` 的原子性即"消费"语义 |
| 时间窗口 | \|服务器 now − ts\| ≤ 120s | 与 TTL 同值；客户端时钟偏差超限直接拒绝 |

前端（node-forge）与后端（JCE）参数必须**成对修改**，互操作回归以 `PasswordCryptoServiceTest` + 冒烟脚本为准。

## 5. 服务端校验顺序（`PasswordCryptoService.verifyAndDecrypt`）

1. `|now − ts| > 120s` → 10006（不消费 nonce）
2. `issued.remove(nonce)` 为空或已过期 → 10006（**原子消费**，重放在此被拒）
3. OAEP 解密失败（密文被篡改/非本公钥加密）→ 10006
4. 解密串首个 `.` 之前的内嵌 nonce ≠ 请求 nonce → 10006（防"旧密文配新 nonce"）
5. 返回明文密码，进入业务校验

设计要点：**挑战类失败（10006）不计入登录限流**——它不证明持有任何密码知识，若计入会让攻击者用垃圾请求把正常用户锁在门外。

## 6. 登录失败限流（`LoginRateLimiter`）

- 维度：用户名（进程内存 `ConcurrentHashMap`）。
- 规则：15 分钟滑动窗口内累计 5 次**密码错误** → 锁定 15 分钟（10007，消息含剩余分钟）；登录成功清零；锁定到期自动半开（下次失败重新计数）。
- 已知取舍：单实例内存态（多实例需共享存储）；键为明文用户名，可被定向恶意锁人——缓解手段是登录前置验证码，列入演进项。

## 7. 前端实现

代码：`frontend/src/api/passwordCrypto.ts`（核心 ~30 行）

- 领取挑战 → `forge.pki.publicKeyFromPem` → `pub.encrypt(nonce+"."+pwd, 'RSA-OAEP', { md: sha256, mgf1: { md: sha1 } })` → `encode64`。
- **为什么用 node-forge 而不是 WebCrypto**：`crypto.subtle` 只在安全上下文（HTTPS / localhost）存在，内网 IP + HTTP 访问时为 `undefined`，登录会整体不可用；forge 纯 JS 环境无关。代价：login/user 两个懒加载 chunk 共享一个 ~79 KB gzip 的 `passwordCrypto` chunk，主包不受影响。
- **node-forge 1.x 的 API 坑**：`encrypt` 是**三参签名**（`data, 'RSA-OAEP', options`），旧文档的两参对象式写法运行时报 `scheme.encode is not a function`；`@types/node-forge` 未声明对象式 options，按三参写即可通过类型检查。
- 登录页对 10006 自动重取挑战重试一次（表单停留超 120s 的常见场景用户无感）；重置/新建入口直接报错由用户再提交。
- `http.ts` 拦截器把业务码挂到 `error.code` 上，供调用方按码分支。
- 密码输入框的值只存在于组件本地状态（`form.password` / `resetPassword`），提交前转换为密文三字段，`update` 用户时不携带任何密码字段。

## 8. 代码地图

| 位置 | 职责 |
|---|---|
| `src/main/java/com/wille/erp/system/service/PasswordCryptoService.java` | 密钥对、挑战签发、四步校验解密 |
| `.../service/LoginRateLimiter.java` | 失败计数与锁定 |
| `.../service/AuthService.java` | 登录编排：限流 → 解密 → BCrypt → 状态 → 会话 |
| `.../service/UserService.java` | create/reset 解密后长度校验；`requireUsernameUnique` 含已删行检查 |
| `.../controller/AuthController.java` | `/auth/crypto`、`/auth/login` |
| `.../controller/SysUserController.java` | reset 入口解密 |
| `.../dto/{LoginRequest,ResetPasswordRequest,UserSaveRequest,CryptoChallenge}.java` | 契约 |
| `.../common/api/ErrorCode.java` | 10001/10004/10006/10007 |
| `.../common/config/SaTokenConfig.java` | `/api/auth/crypto`、`/api/auth/login` 白名单 |
| `frontend/src/api/passwordCrypto.ts` | 挑战领取 + OAEP 加密 |
| `frontend/src/views/login/index.vue` | 登录编排与 10006 重试 |
| `frontend/src/views/system/user/index.vue` | 新建/重置弹窗加密提交 |

## 9. 部署前提与残余风险（必须如实告知）

1. **本方案不能替代 TLS**。它防被动窃听与重放，防不了主动中间人替换公钥。生产部署仍应上 HTTPS（内网可用企业自建 CA），本加密作为纵深防御的第二层。
2. **多实例部署前必须改造两处内存态**：nonce 表、限流计数（建议 Redis，TTL 原生匹配）；RSA 密钥对需共享或接受"请求路由到不同实例时挑战失效"（前端 10006 自动重试可部分兜底，但跨实例公钥不一致会导致间歇失败，应共享密钥）。
3. **时钟依赖**：客户端与服务器偏差 >120s 会拒绝登录（错误信息已提示校时）。内网建议统一 NTP。
4. **种子账号**：`admin/admin123` 为公开知识，上线第一步必须改密。
5. 进程重启瞬间：未消费的 nonce 全部失效，正在提交中的登录会收到一次 10006 并自动重试，属预期行为。

## 10. 验证记录（2026-11-20）

- 后端全量 `mvn test`：**102/102 通过**，其中直接相关：
  - `PasswordCryptoServiceTest`（7）：OAEP 往返、nonce 重放、未知 nonce、过期时间戳、密文绑定他 nonce、垃圾密文；
  - `LoginRateLimiterTest`（4）：阈值、锁定、成功清零、账号隔离；
  - `AuthServiceTest`（10）：含重放拒绝、5 连败锁定后正确密码仍被拒；
  - `SystemControllerTest`（5）：加密建用户、重置重放 10006、解密后短密码 10001；
  - `ArchitectureTest`（7）：分层守护不受影响。
- 端到端冒烟（node-forge 直打 8080，模拟真实前端）：登录/建用户/重置全链路 code 0；重放 10006；短密码 10001；旧版明文请求 10001。
- 前端 `npm run build`（vue-tsc + vite）通过。

## 11. 演进清单（未做，按需排期）

- 国密合规场景：SM2 加密替换 RSA（服务端 BouncyCastle，前端 sm-crypto），接口形状不变。
- 登录前置验证码 / 图形滑块，替代"用户名锁定"的误伤问题。
- 多实例：nonce/限流/密钥迁移 Redis。
- 密码策略（复杂度、有效期、首次登录强制改密）——目前仅长度 ≥6。
- 审计：登录成功/失败/锁定事件落 `sys_audit`（当前仅日志）。
