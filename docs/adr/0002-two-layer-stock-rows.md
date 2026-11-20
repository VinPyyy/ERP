# 双层库存行与双层锁定结构

库存分两层：主行 `inv_stock`（仓库×SKU）是数量、锁定量、均价、成本金额的唯一权威；批次行 `inv_lot_stock`（仓库×SKU×批次）是纯数量分账，不记成本。锁定同样双层：汇总 `inv_stock.locked_qty`（可用量 O(1) 校验）+ 明细 `inv_stock_lock`（订单行级，部分出库/关行/作废时精确归还）。SN 不建每单子表：全局 `inv_sn` 主表存状态，各单据行以 `sn_list` JSON 留永久明细。

## Considered Options

- 单层批次行计价（每批次一行带金额）：等价于批次计价，被 ADR-0001 拒绝。
- 锁定只存汇总：作废/部分出库时无法知道归还谁的量。
- 锁定只存明细：每次可用量校验都要 SUM，高频路径变慢。

## Consequences

必须始终维持不变量（对账器与测试断言）：管批次 SKU 的 SUM(批次行.qty) = 主行.qty；管 SN SKU 的在库 SN 数 = 主行.qty；locked_qty = SUM(明细.remain_qty)；cost_amount ≈ qty × cost_price。金额权威、单价派生：均价永远由 cost_amount/qty 导出，全部出清时结转额取 cost_amount 全额（尾差归零不留残值）。
