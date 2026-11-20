# 冒烟测试方案

> 约定（已写入 CLAUDE.md「文档约定」）：每次冒烟必须落地为 `scripts/smoke/` 内可复跑用例 + 本文件的用例矩阵，禁止一次性脚本跑完即丢。

## 1. 定位

冒烟 = **对运行中的完整系统**（HTTP 入口、真实数据库、真实加密链路）的端到端黑盒验证，与单元测试互补：

| 层 | 范围 | 位置 |
|---|---|---|
| 单测/集成测试 | 进程内，MockMvc + erp_test 库事务回滚 | `src/test/java/**` |
| 冒烟 | 跨进程真实部署：Vite/JCE 互操作、Sa-Token 会话、dev 库落盘行为 | `scripts/smoke/*.cjs` |

冒烟刻意覆盖单测验证不了的缝隙：**前端加密库（node-forge）与 Java JCE 的 OAEP 参数互操作**、真实部署下的错误码语义、逻辑删除在真实唯一索引上的行为。

## 2. 套件清单

### `scripts/smoke/auth-smoke.cjs` —— 认证与密码传输

```
运行：node scripts/smoke/auth-smoke.cjs
退出码：0 全过 / 1 有 FAIL（逐条打印 PASS/FAIL 与期望-实际）
```

**前置条件**
- 后端 dev profile 已启动（默认 `http://localhost:8080`，`ERP_BASE_URL` 可覆盖）
- MySQL 容器 healthy；种子账号 `admin/admin123` 存在
- `frontend/` 已 `npm install`（套件复用 node-forge，路径相对脚本位置解析）

**用例矩阵**

| # | 场景 | 期望 | 关联单测 |
|---|---|---|---|
| 01 | `/auth/crypto` 签发挑战 | publicKey(PEM)/nonce/expiresIn=120 | `PasswordCryptoServiceTest` |
| 02 | forge 加密登录 admin | code 0 + token（**forge↔JCE 互操作主验证**） | 同上（单测用 JCE 自加密，不覆盖 forge） |
| 03 | 同一挑战重放登录 | 首次 0，重放 10006 | `AuthServiceTest.loginWithReplayedNonceRejected` |
| 04 | 错误密码 | 10004 用户名或密码错误 | `AuthServiceTest.loginWrongPasswordThrowsBiz` |
| 05 | 旧版明文 login 请求 | 10001（必填字段缺失） | `AuthControllerTest.loginBlankUsernameReturnsParamInvalid` 同族 |
| 06 | 加密建用户 | code 0 + id | `SystemControllerTest.userCrudFullChain` |
| 07 | 加密重置（打在自建用户上） | code 0 | `SystemControllerTest.resetPasswordReplayAndShortPasswordRejected` |
| 08 | 重置挑战重放 | 10006 | 同上 |
| 09 | 重置短密码（解密后 <6 位） | 10001 含"至少 6 位" | `UserServiceTest` 长度校验 |
| 10 | 自建用户新密码登录 | code 0 | — |
| 11 | 逻辑删除后用户名复用 | 10001 含"曾被使用"（回归 500 缺陷） | `UserServiceTest.createReusingDeletedUsernameThrowsFriendlyError` |
| 12 | 限流：5 败后正确密码仍拒 | 10007 含剩余分钟 | `AuthServiceTest.loginLockedAfterFiveWrongPasswords` |

**副作用与隔离**
- 创建 `smoke_<runid>`、`smoke_reuse_<runid>`、`smoke_lock_<runid>` 三个用户（runid 为 base36 时间戳，天然不与其他运行冲突）；07–10 的重置只打在 `smoke_*` 上，**绝不触碰 admin**，套件中途崩溃不会污染种子账号。
- `smoke_lock_*` 运行后处于限流锁定（≤15 分钟，进程内存态，后端重启即清）。
- 已删的 `smoke_reuse_*` 行留在库里（用户名永久占用是设计语义，见 docs/system/username-lifecycle.md）。

### `scripts/smoke/inventory-smoke.cjs` —— 库存与期初（M3c）

```
运行：node scripts/smoke/inventory-smoke.cjs
前置：同上（后端 8080 + admin 种子）；每次运行新建 runid 仓库/商品，期初最终作废归零，净库存为 0
```

| # | 场景 | 期望 | 关联测试 |
|---|---|---|---|
| 01 | admin 加密登录 | code 0 + token | auth-smoke 01 |
| 02-03 | 建 runid 仓库/商品（mgmtMode 传枚举名 `NONE`——JSON 数字按 ordinal 解析的坑） | code 0 | M2 CRUD |
| 04-05 | 期初建单（含明细）→ 审核 | code 0 | `OpeningServiceTest` |
| 06 | 库存查询 qty=10 金额=50 可用=10 | 断言数值 | `InventoryQueryServiceTest` |
| 07 | 流水 OPENING 单条 balance=10 | 断言 | 同上 |
| 08 | 重复期初审核 | 10001 含"期初" | `duplicateOpeningRowRejected` |
| 09 | replay-check | consistent=true | `FlowReplayCheckerTest` |
| 10-11 | 作废（原因必填）→ 归零且对账仍干净 | qty=0 consistent | `voidReversesBySnapshot` |
| 12-13 | LOT_SN 商品期初审核 → 三级一致（汇总=批次SUM=SN COUNT=2） | 三值相等 | 审查 I5 补位（`lotManagedRequiresLotAndKeepsInvariant` 的冒烟层镜像） |
| 14 | 批次期初作废 → SN 翻回 OUT、批次行归零、对账干净 | consistent=true | `voidLotManagedOpeningReversesLotStock` |

**副作用**：runid 后缀仓库/商品行留在 dev 库（名称唯一不互踩）；期初全部最终作废，库存净变化 0。

### `scripts/smoke/purchase-smoke.cjs` —— 采购链路（M4）

```
运行：node scripts/smoke/purchase-smoke.cjs
前置：后端 8080 + admin 种子；runid 隔离主数据与单据链，收尾入库作废净库存 0
```

| # | 场景 | 期望 | 关联测试 |
|---|---|---|---|
| 01 | admin 加密登录 | code 0 | auth-smoke |
| 02 | runid 仓库/双供应商/商品 | code 0 | — |
| 03 | 询价→审核→双行选中→转单（get 契约 {doc,items}） | 2 张订单草稿 | `convertGroupsSelectedLinesBySupplier`、`getAndPageFollowDocVoShapeWithEnumStatus` |
| 04 | 订单审核→create-inbound→入库审核 | 库存 10@50 | `createInboundCarriesUnfulfilledLines` |
| 05 | done_qty=qty 行完成头完成 | COMPLETED | `partialInboundDoneQtyFlowAndAutoComplete` |
| 06 | 溢收再入 100 件被拒（数量取大不依赖 ratio 种子值）+ 失败草稿直接作废（审查修复批） | 40001 + void code 0 | `overReceiveBoundaryMatrix`、`voidDraftInboundAllowedWithoutStockEffect` |
| 07 | 有源退货 4（手传价 99 被强制原单价） | 库存 6 | `returnWithSourceCarriesPriceAndBlocksOverReturn` |
| 08 | 退货作废回库 | 库存 10 | `voidReturnReversesOutboundDirection` |
| 09 | 入库作废（下游已清） | 库存 0、done 回减 | `voidInboundRollsBackDoneQtyAndStock` |
| 10 | replay-check | consistent=true | `FlowReplayCheckerTest` |

## 3. 执行记录

| 日期 | 套件 | 结果 | 备注 |
|---|---|---|---|
| 2026-11-20 | auth-smoke | 12/12 通过（连续两次运行验证 runid 隔离可复跑） | 由 TEMP 一次性脚本 ×3 合并落地；前身脚本场景全部保留并扩充至 12 例 |
| 2026-11-21 | inventory-smoke | 11/11 通过（连续两次，净库存变化 0） | 首跑暴露 JSON 枚举按 ordinal 解析坑（mgmtMode:1→LOT），改枚举名后全绿 |
| 2026-11-21 | purchase-smoke | 9/9 通过（首轮，×2） | M4 任务 6 首轮 |
| 2026-11-21 | purchase-smoke（审查修复批 10 步） | 10/10 通过（×2；auth 12/12、inventory 回归同步通过） | 首跑步骤 06 假通过暴露环境坑：dev 库 ratio 曾被手工调成 0.1，溢收 1 件合法——改用再入 100 件保证任何正常 ratio 下必拒；同时新步骤实测了 I6 草稿作废 |

## 4. 落地规则（新增冒烟场景时）

1. 优先并入既有套件；主题不同才新建 `scripts/smoke/<主题>-smoke.cjs`（保持断言式 + 退出码约定）。
2. 本文件用例矩阵加行：场景、期望、关联单测、副作用。
3. 冒烟场景若暴露了单测没覆盖的行为，**同时补对应单测**（冒烟负责发现，单测负责固化）。
4. 一次性调试脚本不进仓库也不登记；进了 `scripts/smoke/` 的必须可复跑、自带前置说明。

## 5. 已知边界

- 套件假定单实例后端；多实例部署时 nonce/限流跨实例语义变化（见 docs/security/password-encryption.md §9），需相应改造。
- 冒烟写真实 dev 库，不在 CI 里跑 CI 化版本（CI 用单测层）；如需 CI 冒烟，应配独立库并容器化后端。
