# 销售链路（M5 实现文档 · 一期闭环）

> 2026-11-21 落地。报价→销售订单（审核锁定）→销售出库（核销实扣）→销售退货（SN 回库）四单据 + 锁定生命周期 + 端到端验收。库存/流水/冲正机制见 `docs/inventory/stock-model.md`，状态机纪律见 `docs/purchase/purchase-chain.md` §1（本文只写销售侧增量）。

## 1. 与采购链的对称差异（spec §3e/§8-5）

| 维度 | 采购 | 销售 |
|---|---|---|
| 订单审核 | 不锁库存 | **即锁定**：按仓库×SKU 逐行 `StockService.lock`，整单成败（预检聚合→逐行 FOR UPDATE 权威校验） |
| 履约偏差 | 溢收允许（ratio 参数） | **超发严禁**：出库行 ≤ 订单行剩余量（50001），上限判定进 `addDoneQty` 条件原子 UPDATE 的 WHERE |
| 出库作废 | —（入库作废=冲正） | **重锁**：reverse 回补 qty 后按原 ref 重新 lock + done 回减 + 行/头回退（仅存在被重开行时回头，I2 语义） |
| 退货价格 | 有原单强制原价 | 同；无原单手录、成本按当前均价 |
| SN | 退货出库核销 | 退货**回库**：门面 inbound 自动把 OUT 态 SN 翻回 IN_STOCK、warehouse=退货入库仓、in_doc=退货单（保留 out_doc 作最近出库判据） |

## 2. 锁定生命周期（不变量 locked_qty = SUM(remain_qty)）

| 节点 | 动作 | remain 变化 |
|---|---|---|
| 订单审核 | lock（每行一条锁记录） | += qty |
| 出库审核 | release(源行 ref, 本单量) | −= 出库量（remain=0 保留作历史） |
| 行关闭 | unlock(行 ref) | → 0 |
| 订单作废 | 逐行 unlock | → 0 |
| 出库作废 | **restore(原 ref, 原核销量)**——恢复原行 remain，非新插行 | += 原核销量（已关闭行 40 跳过） |

**单 ref 单行纪律**（M5 审查 C2 修复）：`lock` 只在订单审核插一次；作废重锁走 `StockService.restore` 恢复原行；`release/unlock/restore` 一律先 FOR UPDATE inv_stock 汇总行串行化、再条件原子 UPDATE 明细（`remain_qty>=?` 守卫，M5 审查 C3 修复）——同 ref 永不出现两条活跃锁行，"部分出库→作废→再出库"链路畅通（`partialShipVoidThenReOutboundWorks` 钉住）。

**重锁充足性命题**（审查重点 2 重审）：开关 off 时可用量恒非负，reverse 先回补本单出库量 ⇒ 重锁时 available ≥ 重锁量恒成立，作废不会撞可用量；`voidOutboundRelocksAndRollsBack` + `voidOutboundOnClosedLineDoesNotRelock` 钉住。

## 3. 契约要点

- 报价：`/api/sale/quotes`，整单转一张订单草稿（目标仓数据权限校验），转后 COMPLETED 不可重复；DRAFT/AUDITED 可转（与询价同裁决）。
- 订单：`/api/sale/orders` + audit/void/close/create-outbound；审核接入盘点锁 EXISTS（§3d 明文含销售订单审核，与采购订单不锁对称）。
- 出库：`/api/sale/outbounds`；carrier 空=自提；混合行合法（有源核销锁定、无源部分走可用量校验）；审核链顺序见 §4.1 实现注释。
- 退货：`/api/sale/returns`；配额 AUDITED-only 聚合一次查询；SN 必须 ∈ `returnable-sns`（OUT 且最近出库=源单，§8-3）；`POST /outbounds/{id}/create-return` 按余量出草稿。
- 可用量：`GET /api/sale/availability?warehouseId&skuIds`（OR 权限），前端订单/出库录入实时提示。
- 源单下拉：`/sale/orders/options`（sale:outbound:create）、`/sale/outbounds/options`（sale:return:create）——延续 reference-options 分权思路。

## 4. 代码地图

| 位置 | 职责 |
|---|---|
| `sale/service/QuoteService` | 报价生命周期 + 转单 |
| `sale/service/SaleOrderService` | 订单审核锁定/关闭释放/作废释放/create-outbound |
| `sale/service/SaleOutboundService` | 出库审核链（预检→核销→实扣→done→联动）+ 作废重锁冲正 |
| `sale/service/SaleReturnService` | 退货链 + 配额 + returnable-sns + createFromOutbound |
| `sale/service/SaleAvailabilityService` + `StockService.returnableSns` | 可用量/可退集合（判定单一来源） |
| `sale/event/Sale{Order,Outbound}AuditedEvent` | DocEvent 子类，二期财务钩子（一期发布无监听） |
| `frontend/views/sale/*` | 四组页面（api/sale.ts） |

## 5. 验证记录（2026-11-21，含终审修复批）

- 首轮：`mvn test` 270/270；sale-smoke 10/10×2；`SaleEndToEndTest` 通过（一期验收标准自动化）
- **终审修复批**（3C/4I/8min，全部 Critical+Important 落地）：`mvn test` **274/274**（新增 C2 再履约链、I3 关行不重锁、I4 出库/退货数据权限×2、I2 跨仓拒合并入 I4 例）；sale-smoke 扩至 11 断言（02b=customers/options 回归锁）×2 绿；auth/inventory/purchase 回归绿
- **浏览器可达性验证**（审查建议采纳，M4 browser-checklist 先例）：四个销售页 admin 登录逐页开新增弹窗，客户下拉均有数据、零错误 toast、零 JS 错误（13/13 PASS，脚本 `C:\Users\Pyw\AppData\Local\Temp\erp-m5-sale-pages-check.py`）
- 修复批内容：C1 CustomerController#options 补齐；C2/I3 作废重锁改 `restore` 恢复原行 + 关闭行跳过；C3 release/unlock/restore 全部改"先 FOR UPDATE 汇总行、再条件原子改明细"；I1 退货配额前置 `selectForUpdate` 源出库行串行化；I2 returnable-sns 补仓库可见性；I4 出库/退货数据权限用例；M6 报价空行审核拒

## 6. 已知边界

- 出库价偏离订单价允许（§8-11），差异留 remark。
- 直接出库（无源）不受锁定影响，仅可用量校验；负库存开关行为同 M3 矩阵。
- 退货入库仓可与原出库仓不同（跨仓退到别的仓），数据权限按退货仓校验。
- 报价/订单列表无客户过滤参数（M7 按需）；`create-outbound` 重复点击生成重复草稿（同采购遗留项，M6/M7 评估统一处理）。
- 事件一期只发布不消费；`SaleOrderAuditedEvent` 类名承载 VOIDED action（DocEvent 基类含 Action 枚举，命名权衡已记账本）。
