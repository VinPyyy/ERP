# M4 采购链路

Status: resolved
Blocked by: 04
Spec: ../spec.md（§2.4 purchase 表、§4.1/4.2 时序、§8-11/12/13）

## 任务

- [x] 单据状态机通用支撑（common）：条件状态 UPDATE 防并发（WHERE status=旧态，影响 0 行报 10002）、作废框架（原因必填/下游校验/数量校验/reverse_flow_id 配对冲正/void 三件套留痕/XxxVoidedEvent）、审计字段填充
- [x] 询价单：多供应商报价行（SKU×供应商）、比价透视视图、选中行按供应商分组转采购订单草稿（不碰库存财务）
- [x] 采购订单：溢收比例参数校验（done_qty ≤ qty×(1+ratio)）、done_qty 累计、行关闭、行自动完成、全行终态→头 COMPLETED
- [x] 采购入库单：源单带出（create-inbound 带出未履约行）/无源直接入库；price 默认带出可改（实际结算价）；批次审核时物化 inv_lot；SN 录入；审核调 StockService.in；作废冲正（逆序约束）
- [x] 采购退货单：有原单价按原单不可改/无原单价手录；累计退货量 ≤ 原入库量（有原单时）；成本按当前均价；SN 勾选；审核调 StockService.out
- [x] 硬校验：带 source 的入库单仓库必须 = 订单仓库（§8-12）
- [x] 前端 4 组页面（列表+edit，DocumentPage 组件）

## 验收（TDD：先写测试）

- 状态机转移矩阵（每个合法/非法迁移一条）
- 溢收边界：=、+ε、超比例三档
- 分批入库 done_qty 累计与自动完成；3 供应商比价转 2 张订单
- 入库审核均价正确联动；退货超原量拒绝
- 作废逆序约束：入库单被后续出库消耗时拒绝作废
- 每用例收尾跑对账断言

## Comments
