# 密码传输加密用一次性挑战 RSA-OAEP 字段加密，不依赖"前端 hash"

三个密码入口（登录、重置密码、新建用户）的密码字段不明文出现在网络与请求日志中：前端先 `GET /api/auth/crypto` 领取一次性挑战（RSA-2048 公钥 + nonce + 120s TTL），用 OAEP(SHA-256/MGF1-SHA1) 加密 `nonce.密码` 后提交；服务端按"时间戳窗口 → nonce 一次性 → 解密 → 密文内嵌 nonce 一致"四步校验，配合登录失败限流（5 次/15 分钟锁 15 分钟）。

## Considered Options

- **仅 HTTPS/TLS**：行业标准做法（TLS 下传明文密码是 Google/GitHub/银行的常态），但内网 HTTP 部署短期无法保证，且挡不住请求日志落明文密码。TLS 仍作为部署底线要求，不是本方案的替代项。
- **前端 hash 后传输（如 SHA-256）**：不采纳。hash 值等价于"新密码"，被抓包即可重放（pass-the-hash），安全增益接近零，还会破坏 BCrypt 存储层的既有约定。
- **SRP/PAKE**：密码学上最优（无需 TLS 也防主动中间人），但前后端实现复杂度高一个量级，与 Sa-Token/MyBatis 栈的集成成本不匹配当前阶段。
- **WebCrypto 而非 node-forge**：`crypto.subtle` 仅在安全上下文（HTTPS/localhost）可用，内网 HTTP 部署直接不可用；node-forge 纯 JS 环境无关。

## Consequences

- 挑战状态（nonce 表、限流计数、RSA 密钥对）为**进程内存态**：重启即轮换、多实例部署必须换共享存储（Redis），否则 nonce 校验与限流跨实例失效。
- 密文与 nonce 绑定是防"配新 nonce 重放"的关键：任何改动 payload 格式的代码必须保留内嵌 nonce 校验。
- 主动中间人（替换 `/auth/crypto` 响应中的公钥）不在本方案防御范围内，上线前必须评估 TLS。
- 长度等密码策略校验一律放在服务端解密之后（DTO 里只有密文，对密文做 @Size 无意义）。
