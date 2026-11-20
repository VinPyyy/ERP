# M3 库存核心 + 期初 —— 计划总览（已拆分）

> M3 需求拆为三份独立计划，按依赖顺序执行；每份计划独立可交付、可验证。原单文件计划由本目录三份取代。

| # | 计划 | 内容 | 交付物 | 依赖 |
|---|---|---|---|---|
| A | [m3a-inventory-core](2026-11-20-m3a-inventory-core.md) | 均价计算器、实体/Mapper、StockService 门面（in/out/lock/unlock）、对账器 + property 测试 | 全系统唯一动库存入口可用，四层不变量零差异（纯后端，测试即验收） | 无 |
| B | [m3b-opening](2026-11-20-m3b-opening.md) | 期初单 CRUD/审核/作废冲正 + Excel 导入向导 | 库存可以从 Excel 进账，作废快照冲正自洽 | A |
| C | [m3c-query-frontend](2026-11-20-m3c-query-frontend.md) | 库存三级查询/流水/SN 调整端点 + 前端三页 + 冒烟落地 + 文档收尾 | 用户可见的库存查询、流水、期初页面；inventory-smoke 入库 | A、B |

## 拆分依据

- A 是"地基"：没有门面，B/C 无从谈起；A 的验收不依赖任何业务单据（M4/M5 才产生调用方），靠集成测试 + 200 步 property 测试独立成立。
- B 是"数据入口"：期初是第一个真实使用 A 的场景，审核/作废冲正闭环在 B 内自洽验证。
- C 是"用户价值"：查询与页面放最后，此时 A/B 已把数据链路打通，前端联调和冒烟脚本一次到位。

## 里程碑完成定义（三份计划全部执行完）

1. `mvn test` 全绿（A ≈25 例 + B ≈12 例 + C ≈10 例，含 property）
2. `npm run build` 绿
3. `node scripts/smoke/inventory-smoke.cjs` 全 PASS 且矩阵登记；`auth-smoke.cjs` 回归 12/12
4. `docs/inventory/stock-model.md` 落地，`status.md`/`issues/04` 收尾
