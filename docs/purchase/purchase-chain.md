# 采购链路（M4 实现文档）

> 2026-11-21 落地。询价→订单→入库→退货四单据 + 通用状态机纪律 + 盘点锁接入。库存/冲正/对账机制见 `docs/inventory/stock-model.md`，本文只写采购侧增量。

## 1. 状态机纪律（common 约定，M5/M6 复用）

- 头状态：`DRAFT(10) → AUDITED(20) → COMPLETED(30) / VOIDED(50)`；全部经**条件 UPDATE**（`WHERE status=旧态`，影响 0 行 → 10002 并发冲突），天然幂等防双击。
- 作废：原因必填（10001）+ 下游校验 + 留痕三件套（void_by/time/reason）。
- 行状态（订单行）：10 执行中 / 30 已完成（done≥qty 自动）/ 40 已关闭；全行终态 → 头 COMPLETED。

## 2. 各单据契约

| 单据 | 端点前缀 | 关键规则 |
|---|---|---|
| 询价 CGXJ | `/api/purchase/inquiries` | 不碰库存财务；行=SKU×供应商报价；`select` 比价标记（校验行归属单）；`convert-to-orders(warehouseId)` 按供应商分组生成订单草稿（仓库数据权限校验），转后头 COMPLETED 不可重复转；page 返回实体头、get 返回 `{doc,items}`（M4 审查 C2 统一契约） |
| 订单 CGDD | `/api/purchase/orders` | 审核**不锁库存**（与销售订单对称差异，§3e 采购订单不产生锁定；§3d 盘点锁清单不含采购订单审核）；`close` 行关闭（条件 UPDATE line_status，不回写 doneQty）；`create-inbound` 带出未履约行（qty−done，行价带出）；作废须无未作废下游入库（40003） |
| 入库 CGRK | `/api/purchase/inbounds` | 带源单仓库=订单仓库硬校验（§8-12，**审核时复核**）；源订单须已审核/已完成；保存+审核双层校验源行归属与 SKU 一致（审查 I3）；审核：盘点锁(30002)→溢收预检→批次物化(LotApi)→`StockService.inbound(PUR_IN)`→**done_qty 条件原子累加** `WHERE done+Δ ≤ qty×(1+ratio)`（40001，审查 C1 并发防线）→行/头联动；作废：下游退货校验→`reverse`→done **原子回减**（行回退由 SQL CASE 判定，溢收后部分作废不误回退，审查 I2）；**草稿可直接作废**（审查 I6）；`POST /{id}/create-return` 按可退余量生成退货草稿（spec §5，审查 I7） |
| 退货 CGTH | `/api/purchase/returns` | 有原单：源入库单须 AUDITED（保存+审核双校验）、行归属与 SKU 一致、仓库=原入库仓、**价与批次以源行为准**（用户输入被服务端覆盖，审查 I5）；累计退货**只计已审核单**（含本单、不含草稿与作废，审查 I4）≤ 原入库量(40002)，聚合一次查询；无原单：价手录、管批次按 sku+批号 定位**既有批次**（出库禁造批次）；成本一律当前均价（门面结转）；SN 经门面核销（非整数数量友好拒绝）；作废=reverse 出库方向回冲；草稿可直接作废 |

**仓库数据权限（M4 审查 I1，spec §5）**：订单/入库/退货的 page 按 `currentUser.warehouseScope()` 过滤（零仓库用户查空）、get/audit/void/create 校验仓库可见（NO_PERMISSION）；询价头无仓库维度，`convert-to-orders` 的目标仓库校验可见性。

## 3. 代码地图

| 位置 | 职责 |
|---|---|
| `purchase/service/InquiryService` | 草稿/审核/选中/分组转单 |
| `purchase/service/OrderService` | 订单生命周期 + createInbound + 完成联动 |
| `purchase/service/InboundService` | 入库审核链（锁/溢收/物化/门面/联动）+ 作废回退 |
| `purchase/service/ReturnService` | 退货链 + 累计量校验 |
| `inventory/api/LotApi` `CountLockGuard` | 批次物化复用 / 盘点锁 EXISTS（ADR-0004） |
| `frontend/views/purchase/*` | 四组列表+明细+创建页（api/purchase.ts） |

## 4. 验证记录（2026-11-21）

- **终审修复批**（审查 2 Critical + 9 Important，全部落地）：`mvn test` **220/220**（新增 17：溢收后作废状态组合 I2、越界源行 I3、草稿源订单拒 I3、SN 非整数 M5、草稿作废 I6×2、仓库范围 I1、配额 AUDITED-only I4、退货=原量档、盘点锁拒退货、批次退货三例 I5、create-return I7、询价契约 C2+M1×2）
- 修复批并发防线：done_qty 累加上限判定进 `UPDATE ... WHERE done+Δ≤limit`（单语句原子），并发审核同订单行后到者 0 行→40001 回滚；行为级并发用例需要双事务线程（与 erp_test 回滚隔离冲突），以 SQL 条件原子性 + 顺序超限用例覆盖，记入账本
- C2 修复经浏览器实测询价全流程（见 `.scratch/m4-review-fixes/browser-checklist.md`）
- `npm run build` 绿；`purchase-smoke.cjs` 10 步（新增溢收拒绝+草稿作废清理步骤；执行记录见 smoke 矩阵）
- 首轮记录：`mvn test` 203/203（M4 新增 15）；回归 `inventory-smoke`、`auth-smoke` 通过

## 5. 已知边界

- 询价/订单列表无供应商/仓库过滤参数（一期规模小，M7 联调按需补）。
- 订单编辑仅整单替换行；行级编辑（改单不删他行）未做——已审核单本就不能改。前端四页暂不做草稿编辑入口（后端 update 可用，草稿可作废兜底），M5 联调时统一补。
- 溢收 ratio 作用于"带源行"；无源入库不受溢收约束（语义即自由入库）；带源单允许混合行（无源行不参与联动）。
- 盘点锁在 M6 盘点单 UI 落地前只能被测试构造；审核链已就位。
- `create-inbound` 生成的草稿若再次点击会重复生成（未做"已存在未作废草稿"去重）——M5 联调时评估。
- 询价 DRAFT 可直接转单（规格未禁止；订单自身仍需审核，风险可控——账本裁决）。
- 采购退货 SN 不做"必须属于原入库行 sn_list"强校验（§8-3 勾选裁决明文针对销售；门面校验 IN_STOCK/仓库/批次兜底）。
- page/get 直接返回实体（含审计字段）；page 排序 id DESC（与 M3 期初一致，跨模块统一项挂账）。
