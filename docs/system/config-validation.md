# 系统参数按类型校验（BOOL/DECIMAL/INT/STRING）

> 2026-11-20 落地。`sys_config.value_type` 决定"值"字段的合法输入；前端控件限制 + 后端写入口校验双层拦截。背景：脏值一旦入库，`getDecimal/getInt` 在业务读参数时抛 `NumberFormatException` → 全站 500，且报错远离根因，极难排查。

## 1. 行为契约

| 类型 | 合法值 | 前端控件 | 后端校验 |
|---|---|---|---|
| BOOL | 仅 `true` / `false`（严格小写） | 下拉二选一，无法自由输入 | 非 `true/false` → `10001 布尔参数只能填 true/false` |
| DECIMAL | 合法十进制数（`0.1`、`1`、`-2.5`） | 数字输入框 + 失焦/提交校验（`/^-?\d+(\.\d+)?$/`） | `new BigDecimal` 解析失败 → `10001 小数参数格式不正确: x` |
| INT | 整数（`4`、`-1`） | 整数校验（`/^-?\d+$/`） | `Integer.parseInt` 失败 → `10001 整数参数格式不正确: x` |
| STRING | 任意非空 | 普通输入 | 仅非空 |
| 全部 | 不允许空/空白 | 提交前拦截 | `10001 参数值不能为空` |

大小写说明：`Boolean.parseBoolean("TRUE")` 会静默返回 false——**后端刻意严格拒绝** `TRUE/False` 等变体，避免"看起来开了实际没开"的静默错值。

## 2. 代码地图

| 位置 | 职责 |
|---|---|
| `src/main/java/com/wille/erp/common/enums/ConfigValueType.java` | 类型枚举（BOOL/DECIMAL/INT/STRING） |
| `.../system/service/ConfigServiceImpl.java` `validateByType` | **正确性层**：唯一写入口 `update()` 先校验后落库刷缓存 |
| `frontend/src/views/system/config/index.vue` | **体验层**：BOOL→el-select；DECIMAL/INT→正则校验 + 字段下红字提示（blur 即检、input 即清） |
| `frontend/src/api/system.ts` `ConfigVO` | 携带 `valueType` 供前端分支 |

两层规则同源（同一正则语义）；改规则时两处必须同步改。

## 3. 验证记录（2026-11-20）

- `ConfigServiceTest` 新增 4 例（共 8/8 通过）：BOOL 拒 `yes`/`TRUE`、DECIMAL 拒 `abc`/`1.2.3`、空值拒绝、合法值通过并即时生效。
- 全量 `mvn test` 通过；前端 `npm run build` 通过。

## 4. 已知边界

- 校验只管"格式"，不管"业务范围"（如 `purchase.over.receive.ratio` 允许负数吗？spec 语义是 0~1）。范围约束需要按 key 注册规则，列入待办。
- 新增参数（建 key）走 Flyway 迁移，不经过 `update()`；迁移里的值需人工保证格式正确。
- 前端校验可被绕过（直接调 API），后端层是最终防线——这正是双层设计的目的。
