# M3 库存核心 + 期初（全系统地基，测试最重）

Status: resolved
Blocked by: 03
Spec: ../spec.md（§2.3 inventory 表、§3a/3b/3c/3f 模型规则、§6-M3）；ADR 0001/0002/0003/0005

## 任务

- [x] 五件套实体/Mapper：inv_stock（主行）、inv_lot_stock（批次行）、inv_sn、inv_stock_lock、inv_flow（insert-only）+ inv_lot 批次主档物化
- [x] **StockService 门面**（inventory.api，全系统唯一动库存入口）：
  - in/out：均价计算器（纯函数，inventory.core）——金额权威、单价派生、出清归零；FOR UPDATE 按 (warehouse_id, sku_id) 升序加锁（ADR-0005）
  - lock/unlock/release：汇总+明细双层（ADR-0002）
  - available：可用量 = qty − locked_qty；负库存参数 stock.allow.negative
  - SN 物化/翻转（§3b 两态生命周期）
  - 每笔写 inv_flow（带符号 qty/cost_amount + price 与 cost 两线 + balance_* 快照 + biz_* 溯源 + reverse_flow_id）
- [x] 对账器（inventory.core）：流水重放比对四层不变量 + replay-check 管理端接口
- [x] 期初单（inv_opening/item）：CRUD + Excel 导入（含批次/SN/期初均价）+ 审核/作废；约束：同仓库×SKU 仅一张非作废期初行、有后续流水禁作废
- [x] 库存查询接口（汇总→批次→SN 三级，成本列按 inventory:cost:view 权限置 null）+ 流水查询 + SN 人工调整接口（§8-9，必填原因留痕）
- [x] 前端：库存查询页（三级下钻）、流水页、期初页（Excel 导入向导）

## 验收（TDD：先写测试，红→绿→重构）

- 均价计算器纯函数单测（含出清尾差归零）；手算样例：入10@10→入5@13→出8 = 均价11、结转88、余7@11额77
- StockService 集成测试族：**随机操作序列 property 测试**（任意 in/out/lock 序列断言四层不变量 + 重放一致）
- 可用量/负库存开关矩阵；SN 重复在库报错/出库核销/退货回库翻转
- 期初导入（含批次 SN）审核后库存/流水/SN 三层正确；重复导入拒绝；replay-check 零差异

## Comments

## Comments（续）

- 2026-11-21 完成。三计划拆分执行（docs/superpowers/plans/2026-11-20-m3{a,b,c}-*.md），最终审查 M3a 0C/10I、M3b 1C/9I 全修；验收锚点全绿：均价手算样例、property 200 步、期初导入审核三层正确、重复拒绝、replay-check 零差异、SN 三态。冒烟 inventory-smoke 11/11 已落地。已知偏差与延后项见 docs/inventory/stock-model.md §9 与 .superpowers 账本。
