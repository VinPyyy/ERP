# 用户名唯一性与逻辑删除（含"曾被使用"语义）

> 2026-11-20 修复落地（commit `f32cb1d`）。记录用户名在"活跃 / 已逻辑删除"两种状态下的占用规则、错误契约与实现位置。

## 1. 背景与缺陷

`sys_user` 的唯一索引 `uk_sys_user_username` **只建在 `username` 列上**，不含逻辑删除标记 `deleted`（`BaseEntity` 的 `@TableLogic` 字段，0 活跃 / 1 已删）。而服务层查重走 MyBatis-Plus 包装查询，`@TableLogic` 自动附加 `deleted = 0`，**看不见已删行**。

后果：删除用户 `zhang3` 后再新建 `zhang3`，服务层查重通过 → insert 撞唯一索引 → `SQLIntegrityConstraintViolationException` → 全局异常处理器兜底 **500**。缺陷由密码加密改造的冒烟测试偶然暴露（测试数据 `smoke_rp` 逻辑删后复用触发）。

## 2. 行为契约（修复后）

| 场景 | 结果 |
|---|---|
| 新建/改名占用**活跃**用户名 | `10001 用户名已存在: <name>` |
| 新建/改名占用**已逻辑删除**的用户名 | `10001 该用户名曾被使用: <name>` |
| 编辑时保持自己的用户名 | 通过（查重排除自身 id） |
| 删除后重建同名 | 拒绝，见上一行——**用户名一经使用永久占用** |

"永久占用"是有意选择，不是权宜：历史单据、审计记录里的 `create_by`/操作人引用的是用户 id，允许用户名复用会让"zhang3 的旧单据"在新 zhang3 的语境下产生歧义。若未来业务确需复用，正确做法是迁移唯一索引为 `(username, deleted_at)` 之类可区分多次删除的形态，而不是放开服务层校验。

## 3. 实现（代码地图）

| 位置 | 职责 |
|---|---|
| `src/main/java/com/wille/erp/system/mapper/SysUserMapper.java` | `countDeletedByUsername`：`@Select` raw SQL 直查 `deleted = 1`，**绕过 `@TableLogic` 自动过滤**（MP 包装查询做不到） |
| `.../service/UserService.java` `requireUsernameUnique` | 先查活跃行（排除自身）→ 再查已删行；两段分别给出不同文案 |
| `.../service/UserService.java` `create` / `update` | 唯一入口调用查重；无其他旁路 |

顺序要点：活跃查重在前、已删查重在后——同名活跃行优先报"已存在"（更准确）。

## 4. 验证记录（2026-11-20）

- 单测 `UserServiceTest.createReusingDeletedUsernameThrowsFriendlyError`：create → delete → 同名 create 抛 `BizException` 且消息含"曾被使用"。全量 `mvn test` 102/102。
- 实测（dev 库存在逻辑删行 `smoke_rp`）：`POST /api/system/users` 重建 `smoke_rp` → `10001 该用户名曾被使用: smoke_rp`（修复前为 500）。

## 5. 已知边界

- 该规则同样作用于 `update` 改名路径（改名撞上已删用户名同样拒绝）。
- 物理删除（DBA 手工 DELETE）可释放用户名，属运维操作，不在应用语义内。
- 其他带逻辑删除 + 唯一索引的表（如 `sys_role.role_code`）存在同类隐患，未逐一排查——列为待办，新增表时唯一索引必须显式考虑 `deleted` 列。
