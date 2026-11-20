# M3c 库存查询 + 前端三页 + 冒烟收尾实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 subagent-driven-development 或 executing-plans 逐任务实现。步骤用复选框跟踪。

**目标：** 库存三级下钻查询/流水/SN 人工调整端点 + 前端三页（库存查询、库存流水、期初库存）+ `inventory-smoke.cjs` 冒烟落地 + 文档与状态收尾，M3 里程碑验收。

**架构：** 查询走独立 `InventoryQueryService`（不碰门面），仓库数据权限经 `CurrentUserHolder.warehouseScope()`，成本列后端按 `inventory:cost:view` 置 null；前端遵循 layout-system §4 接入规范（erp-page/PageHeader/erp-toolbar/erp-table/erp-pager + 按钮图标）；冒烟按项目规则落地 `scripts/smoke/` 并登记矩阵。

**技术栈：** Spring Boot 3 + MyBatis-Plus；Vue3 + Element Plus + vite。

**规格：** `.scratch/erp-phase1/spec.md` §2.3/§5（库存端点）/§7（路由）/§8-9 + `issues/04`。前置：[m3a](2026-11-20-m3a-inventory-core.md)、[m3b](2026-11-20-m3b-opening.md) 已完成。总览：[索引](2026-11-20-m3-inventory-opening.md)

## 全局约束

- 查询接口必须应用仓库数据权限（scope null=全部，空集=查空）；排序固定（stocks 按 sku_code，flows 按 create_time DESC），不开放通用 sort。
- 成本列脱敏在服务端（不是前端隐藏）。
- SN 人工调整只改状态/仓库 + reason 必填落 remark，不动数量（数量差由盘点修正，spec §8-9）。
- 前端新页面路由 meta.title 与菜单、页头三处同名（项目约定）。
- 冒烟脚本：断言式、FAIL 退出码非 0、runid 数据隔离、矩阵登记（项目规则）。

## 审查重点（Review Focus）

1. 成本脱敏绕过：直接调 API 无 `inventory:cost:view` → costPrice/costAmount 必须为 null（前端隐藏只是第二重）→ 任务 1 `costColumnMaskedWithoutPerm`。
2. 数据权限穿透：绑仓 A 用户传 warehouseId=B 查询 → 空结果而非 B 数据 → 任务 1 `warehouseScopeApplied`。
3. 三级下钻一致性：汇总行 qty 与其批次行 SUM、SN COUNT 对得上 → 任务 2 冒烟 06 步。
4. SN 调整留痕：无 reason → 10001；调整后 update_by/remark 可审计 → 任务 1 `snAdjustRequiresReason`。
5. 冒烟可复跑：runid 隔离、重复执行不互踩 → 任务 3 连续跑两次。

---

### 任务 1：查询端点 + SN 调整

**文件：** 创建 `inventory/service/InventoryQueryService.java`、`inventory/controller/InventoryQueryController.java`、`inventory/dto/`（StockQuery/StockVO/LotStockVO/SnQuery/SnVO/FlowQuery/FlowVO/SnAdjustRequest）；测试 `InventoryQueryControllerTest`（MockMvc）+ `InventoryQueryServiceTest`

- [ ] 步骤 1：写失败测试：`costColumnMaskedWithoutPerm`；`warehouseScopeApplied`（含 warehouse_id=0 全仓用户）；`availableQtyDerived`（qty−locked）；`snAdjustRequiresReason`；`snAdjustFlipsStateAndAudits`；`flowsFilterByBizTypeAndDate`；权限 403 各端点 1 例
- [ ] 步骤 2：红
- [ ] 步骤 3：实现端点：`GET /api/inventory/stocks`、`GET /stocks/lots`、`GET /sns`、`GET /flows`、`POST /sns/{id}/adjust`（`inventory:sn:adjust`）。分页沿用 `PageQuery/PageResult`
- [ ] 步骤 4：`mvn test` 绿
- [ ] 步骤 5：Commit `feat(inventory): stock/lot/sn/flow queries with cost masking and sn adjust`

### 任务 2：前端三页 + 路由

**文件：** 创建 `frontend/src/api/inventory.ts`、`views/inventory/stock/index.vue`（汇总表格→行展开批次子表→SN 抽屉）、`views/inventory/flow/index.vue`（过滤+快照列）、`views/inventory/opening/index.vue`（列表+草稿弹窗+导入向导+审核/作废）；修改 `router/index.ts`（`/inventory/stock|flow|opening` 三路由 + meta.title=库存查询/库存流水/期初库存）

- [ ] 步骤 1：实现三页（遵循 layout-system §4：PageHeader accent、按钮图标按 §3 映射、erp-* 类、成本列 `v-if` 权限 + 后端脱敏双保险；期初导入向导复用 M2 交互模式：上传→行级错误表→重传）
- [ ] 步骤 2：`npm run build` 绿
- [ ] 步骤 3：浏览器手工过：期初建单→导入→审核→库存下钻三级→流水快照列→SN 调整留痕
- [ ] 步骤 4：Commit `feat(frontend): inventory stock/flow/opening pages`

### 任务 3：冒烟落地 + 文档收尾

**文件：** 创建 `scripts/smoke/inventory-smoke.cjs`；修改 `docs/testing/smoke-test-plan.md`（矩阵登记）、`docs/inventory/stock-model.md`（新建：门面契约/均价规则含手算样例/双层锁定/冲正策略/对账用法/代码地图/验证记录/已知边界）、`.scratch/erp-phase1/status.md`（M3 ✅ + 测试基线）、`issues/04`（勾选、Status→resolved）

- [ ] 步骤 1：写冒烟（断言式，runid 隔离，退出码约定）：01 登录→02 建期初草稿→03 API 建行→04 审核→05 stocks 查询 qty/均价→06 lots/sns 下钻与汇总一致→07 replay-check `consistent=true`→08 重复期初拒绝 10001→09 作废→10 主行回 0 且 replay-check 仍零差异
- [ ] 步骤 2：`node scripts/smoke/inventory-smoke.cjs` 连续两次全 PASS（可复跑）；`auth-smoke.cjs` 回归 12/12
- [ ] 步骤 3：写 `docs/inventory/stock-model.md` + 更新 status/issues
- [ ] 步骤 4：全量验证：`mvn test`（记录新基线）+ `npm run build`
- [ ] 步骤 5：Commit `docs(inventory): inventory smoke suite, stock model doc and M3 closure`
- [ ] 步骤 6：push + main 同步（需人确认）

---

## M3 里程碑完成定义（三份计划累计）

1. `mvn test` 全绿（A+B+C 新增 ≈47 例，含 200 步 property）
2. `npm run build` 绿；浏览器三页可用
3. `inventory-smoke` 全 PASS 且矩阵登记；`auth-smoke` 回归绿
4. `docs/inventory/stock-model.md` 落地；`status.md` M3 ✅；`issues/04` resolved
