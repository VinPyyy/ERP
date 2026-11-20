# M2 基础资料 + Excel 导入

Status: resolved
Blocked by: 02
Spec: ../spec.md（§2.2 basedata 表、§8-15）

## 任务

- [x] 商品 CRUD：sku_code 唯一、四种 mgmt_mode（1 不管/2 管批次/3 管 SN/4 批次+SN）、shelf_life_days（管批次必填）、expire_warn_days（预埋只存不算）、ref_sale_price、启停用、逻辑删
- [x] **mgmt_mode 变更约束**：已有库存/流水/单据引用后禁改（应用层校验 + 测试固化）；删除引用校验同理
- [x] 商品类别（树）、客户（含 tax_no 二期预埋）、供应商、仓库 CRUD
- [x] EasyExcel 导入 ×3（商品/客户/供应商）：全量校验、有错不落库、输出行级错误报告 Excel；导入导出收敛到 XxxExcelService（备将来换 FastExcel，§8-16）
- [x] 列表导出
- [x] 前端 5 页：商品（左类别树+右列表+导入/导出/编辑弹窗）、客户、供应商、仓库、类别管理

## 验收（TDD：先写测试）

- ProductService：编码唯一/模式变更约束/删除引用校验
- ImportService：真实 xlsx fixture 四份（全对/含错/重复编码/模式非法）
- 1000 行商品导入含错误行 → 返回精确行号错误报告

## Comments

- 2026-11-20 完成：mvn test 85/85 绿，npm build 通过。Excel 导入含真实 fixture 程序化生成测试；mgmt_mode 引用约束经事件监听落地。裁决见账本。
- 2026-11-20 补验收：1000 行规模导入测试（第 500 行文件内撞重 → 行号精确报 500、整批零落库）通过，ProductExcelImportTest 8/8。
