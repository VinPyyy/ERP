# 采购链路（M4 实现文档）

> 2026-11-21 落地。询价→订单→入库→退货四单据 + 通用状态机纪律 + 盘点锁接入。库存/冲正/对账机制见 `docs/inventory/stock-model.md`，本文只写采购侧增量。

## 1. 状态机纪律（common 约定，M5/M6 复用）

- 头状态：`DRAFT(10) → AUDITED(20) → COMPLETED(30) / VOIDED(50)`；全部经**条件 UPDATE**（`WHERE status=旧态`，影响 0 行 → 10002 并发冲突），天然幂等防双击。
- 作废：原因必填（10001）+ 下游校验 + 留痕三件套（void_by/time/reason）。
- 行状态（订单行）：10 执行中 / 30 已完成（done≥qty 自动）/ 40 已关闭；全行终态 → 头 COMPLETED。

## 2. 各单据契约

| 单据 | 端点前缀 | 关键规则 |
|---|---|---|
| 询价 CGXJ | `/api/purchase/inquiries` | 不碰库存财务；行=SKU×供应商报价；`select` 比价标记；`convert-to-orders(warehouseId)` 按供应商分组生成订单草稿，转后头 COMPLETED 不可重复转 |
| 订单 CGDD | `/api/purchase/orders` | 审核**不锁库存**（与销售订单对称差异）；`close` 行关闭；`create-inbound` 带出未履约行（qty−done，行价带出）；作废须无未作废下游入库（40003） |
| 入库 CGRK | `/api/purchase/inbounds` | 带源单仓库=订单仓库硬校验（§8-12）；审核：盘点锁(30002)→溢收 `done+Σ本次 ≤ qty×(1+ratio)`(40001)→批次物化(LotApi)→`StockService.inbound(PUR_IN)`→done/行/头联动；作废：下游退货校验→`reverse`→done 回减+行/头回退 |
| 退货 CGTH | `/api/purchase/returns` | 有原单：仓库=原入库仓、价强制原单价、累计退货（含本单、不含作废）≤ 原入库量(40002)；无原单：价手录；成本一律当前均价（门面结转）；SN 经门面核销；作废=reverse 出库方向回冲 |

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

- `mvn test` **203/203**（M4 新增 15：询价 3、订单入库 10、退货 5——含溢收三档、分批 done、仓库硬校验、盘点锁、无源直入、SN 退货核销、双向作废链）
- `npm run build` 绿；`purchase-smoke.cjs` 9 步（执行记录见 smoke 矩阵）
- 回归：`inventory-smoke`、`auth-smoke` 通过

## 5. 已知边界

- 询价/订单列表无供应商/仓库过滤参数（一期规模小，M7 联调按需补）。
- 订单编辑仅整单替换行；行级编辑（改单不删他行）未做——已审核单本就不能改。
- 溢收 ratio 作用于"带源行"；无源入库不受溢收约束（语义即自由入库）。
- 盘点锁在 M6 盘点单 UI 落地前只能被测试构造；审核链已就位。
- `create-inbound` 生成的草稿若再次点击会重复生成（未做"已存在未作废草稿"去重）——M5 联调时评估。
