# 库存模型（M3 实现文档）

> 2026-11-21 落地（M3a 内核 / M3b 期初 / M3c 查询与前端）。本文是动库存这条命脉的单一事实入口：门面契约、均价规则、双层锁定、快照冲正、对账器、SN 生命周期、查询脱敏。决策依据见 ADR 0001/0002/0003/0005 与 spec §3。

## 1. 门面契约（`inventory.api.StockService`）

全系统唯一动库存入口；ArchUnit R4b 强制 purchase/sale 只能经此门面。**边界纪律**：写方法只能跨 bean 经 Spring 代理进入；本类私有辅助（move/lockOrCreate/writeBack/insertFlow/applyLotDelta/applySnReverse）共享调用方事务——public `@Transactional` 方法禁止 `this.` 自调用（代理绕过、注解静默失效）。

| 方法 | 语义 | 关键校验 |
|---|---|---|
| `inbound(MoveCmd)` | 入库：金额 += round2(qty×price)，均价派生 | 商品存在、price 非空、批次/SN 形态匹配 |
| `outbound(MoveCmd)` | 出库：按均价结转，出清结全额 | 负库存开关（30001）、批次足量（30006）、SN 状态/仓库/批次一致（30004） |
| `lock(LockCmd)` | 订单审核锁定：明细+汇总双层 | qty>0；可用量不足 30001 整批回滚 |
| `unlock(LockRef)` | 按订单行全额释放 | remain=0 行保留作历史 |
| `release(LockRef, qty)` | 部分核销（锁定转实扣，M5 出库用） | qty ≤ remain |
| `reverse(ReverseCmd)` | 作废冲正：取未配对原流水按快照反向 | 已冲正拒绝（CONCURRENT_CONFLICT）；入库类冲正前 qty−locked ≥ 原量（30003） |
| `available(wh, sku)` | qty − locked_qty | — |

## 2. 均价规则（spec §3a，`core.AveragePriceCalculator` 纯函数）

- 粒度 = 仓库×SKU，批次/SN 不参与计价（ADR-0001）
- **金额权威、单价派生、出清归零**：`qty_out == qty` 时结转额 = `cost_amount` 全额（吃尾差）；出清后 (0,0,0)
- 数学性质：按均价出库不改变 cost_price；只有入库价 ≠ 均价才重算
- 手算锚点：入10@10 → 入5@13 → 出8 = 均价 11、结转 88、余 7@11 额 77

## 3. 双层锁定（spec §3e，ADR-0002）

汇总 `inv_stock.locked_qty` + 明细 `inv_stock_lock`（订单行→一行，remain 剩余），同事务维护，不变量 `locked_qty = SUM(remain_qty)`。锁定不吃盘点锁、盘点快照不减锁定（盘亏可致可用量为负，仅预警）。

## 4. 快照冲正（ADR-0003，`reverse`）

库存行永远等于流水重放终态：逆向金额一律取原流水 `cost_amount` 快照，绝不反算、绝不用当前均价冲历史。冲正行 `reverse_flow_id` 指向原行且互斥（二次冲正拒绝）。**净零流水对不再阻断期初作废**（M3b 审查 I8 裁决：逆序作废出路优先于 §4.2 字面）。SN 冲正：`out_doc_id IS NULL`（从未出库=本单新建）物理删除；曾出库被翻回的恢复 OUT 态保留审计链——退货翻回**不清 out_doc**，它是持久的"曾出库"判据。

## 5. SN 生命周期（spec §3b）

两态 `IN_STOCK ⇄ OUT`：入库物化（重复在库 30005 / OUT 翻回更新仓库批次）、出库核销（校验状态+仓库+批次，置 OUT 清仓库）、退货回库翻回（保留出库指向）。人工调整（`POST /sns/{id}/adjust`）只改状态/仓库 + 原因必填落 remark，不写流水——数量差由盘点修正（§8-9 裁决）。

## 6. 流水与对账（spec §3f）

`inv_flow` insert-only：带符号 qty/cost_amount、price（业务价）与 cost（计价价）两线分离、balance_* 变动后快照、biz_* 溯源、reverse_flow_id 配对。`core.FlowReplayChecker` 纯函数带符号累加重放；`StockReconcileService` 四层不变量（批次 SUM / SN 计数 / 锁定 SUM / 重放链+终态，迭代域=库存行∪流水键），`@Transactional(readOnly)` 取一致性快照。管理端 `GET /api/inventory/flows/replay-check`（`inventory:flow:replay`）。测试收尾统一走 `StockInvariantAsserter.assertConsistent()`。

## 7. 查询与脱敏（spec §5）

`GET /api/inventory/stocks|stocks/lots|sns|flows`：仓库数据权限 `warehouseScope()`（空集查空、null 全仓）；成本列无 `inventory:cost:view` 时**后端置 null**；排序 (warehouse, sku)（sku_code 排序为已知偏差，见 §9）；分页沿用 PageQuery 上限 200。

## 8. 代码地图

| 位置 | 职责 |
|---|---|
| `inventory/core/` | AveragePriceCalculator、StockState、FlowReplayChecker、ReplayDiff（纯函数） |
| `inventory/api/` | StockService + StockCmd（跨模块唯一契约，裸 id/枚举） |
| `inventory/service/` | StockServiceImpl、StockReconcileService、OpeningService、InventoryQueryService |
| `inventory/controller/` | InventoryQueryController、InventoryFlowController、OpeningController |
| `inventory/excel/` | OpeningExcelService + OpeningImportModel |
| `test/.../support/StockInvariantAsserter` | 四层不变量断言（委托 ReconcileService） |
| `frontend/views/inventory/` | stock（三级下钻）/ flow（快照列）/ opening（导入向导） |
| `scripts/smoke/inventory-smoke.cjs` | 期初→审核→查询→重复拒绝→对账→作废归零 11 断言 |

## 9. 已知边界与残余风险

- **加锁顺序**：cmd 内单仓按 skuId 升序；跨仓单据（调拨）必须拆两次门面调用并按仓序执行——M6 落地时审查。
- **排序偏差**：库存查询按 (warehouse_id, sku_id) 而非 spec 的 sku_code（避免跨模块 join），前端可接受则维持，否则需 basedata 提供批量 id→code 排序接口。
- **对账性能**：全表扫 + 每行 product 读，一期规模（≤10 仓、流水年 10⁵）秒级；SKU 上万需 GROUP BY 聚合 SQL 改造。
- **reverse 出库方向**（作废销售出库回冲）已实现但测试由 M4/M5 用例驱动（账本裁决）。
- **盘点锁 EXISTS** 尚未接入各审核入口（M6 回改，spec §3d）。
- SN 人工调整是**有意的不变量逃生门**：调错会破坏 SN 计数=主行 qty，靠盘点纠偏；操作人权限点单独控制。

## 10. 验证记录（2026-11-21）

- `mvn test` **174/174**（M3 新增 88 例：均价 6、move 10、lock 6、property 200 步、对账 4、期初 15+5、Excel 5、查询 8、并发 2）
- `npm run build` 绿；`node scripts/smoke/inventory-smoke.cjs` 11/11（连续两次，runid 隔离）；`auth-smoke.cjs` 回归 12/12
- 最终审查：M3a（0C/10I 全修）、M3b（1C/9I 全修）；M3c 审查见账本
