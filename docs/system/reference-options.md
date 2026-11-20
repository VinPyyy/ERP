# 下拉引用数据的权限设计（/options）

> 2026-11-21 落地。背景：只授"用户管理"模块的角色登录后进用户页报 403——页面挂载即调 `GET /system/roles`（要求 `system:role:list`）。同类隐患遍布采购/库存页的仓库、供应商、商品下拉。

## 1. 决策

**引用数据下拉与管理列表分权**：

- 管理页的 list/page 端点保持模块 `*:list` 权限不变（返回全字段、分页、可导出）。
- 下拉所需的裁剪视图走新端点，**登录即可**（Sa-Token 全局拦截器兜底，无 `@SaCheckPermission`）——下拉数据是组织性引用信息（id+编码+名称），不含联系人/价格/成本等敏感字段。
- 例外：单据源单下拉（订单→入库、入库→退货）属**交易数据**，不放登录级，用权限点 OR 精确放行"能创建下游单"的角色。

## 2. 端点契约

| 端点 | 权限 | 返回（裁剪字段） |
|---|---|---|
| `GET /api/system/roles/options` | 登录即可 | id, roleCode, roleName |
| `GET /api/basedata/warehouses/options` | 登录即可 | id, code, name（仅启用） |
| `GET /api/basedata/suppliers/options` | 登录即可 | id, code, name（仅启用） |
| `GET /api/basedata/products/options` | 登录即可 | id, skuCode, name, unit（**无价格成本**，仅启用） |
| `GET /api/purchase/orders/options` | `purchase:inbound:create` 或 `purchase:return:create`（SaMode.OR） | id, docNo, supplierId, warehouseId（仅 AUDITED） |
| `GET /api/purchase/inbounds/options` | `purchase:return:create` | 同上（仅 AUDITED） |

customers/options 待 M5 销售用到时补。

## 3. 代码地图

- 后端：`SysRoleController#roleOptions`、`WarehouseController#options`、`SupplierController#options`、`ProductController#options`、`OrderController#options`、`InboundController#options`；VO 集中在 `basedata/dto/OptionVOs`、`system/dto/RoleOptionVO`、`purchase/dto/DocOptionVO`；service 侧 `SupplierService#listAllActive`、`ProductService#listAllActive`、`Order/InboundService#auditedOptions`
- 前端：`api/system.ts`、`api/basedata.ts`、`api/purchase.ts` 各加 `options()`；消费点=用户页角色、采购 4 页（供应商/仓库/商品/源单）、库存 3 页仓库筛选
- 测试：`SystemControllerTest#roleOptionsLoginOnlyWhileListStaysPermissioned`、`BasedataControllerTest#referenceOptionsEndpointsAreLoginOnly`、`PurchaseOptionsEndpointTest`（2 例）、auth-smoke 步骤 13

## 4. 验证记录（2026-11-21）

- `mvn test` **227/227**（新增：options 契约 2 例、admin 正向 2 例、受限负向 2 例、auditedOptions 内容 1 例、404 handler 1 例）；`npm run build` 绿
- auth-smoke **13/13**（步骤 13=受限用户 roles 403 + options 200）；inventory/purchase 回归绿
- **浏览器实测原始 bug 场景**：仅授 `system:user:list` 的角色登录 → 进用户管理页 0 报错 toast、列表渲染、新增按钮按权限隐藏（4/4 PASS，JS 错误 0）；脚本与截图留痕 `.scratch/perms-options/`
- 测试设计注记：MockMvc 请求中 Sa-Token 拦截器的权限查询读不到测试事务未提交的 grant 行（连接可见性，套件 harness 限制、生产无此问题）——正向断言因此走 admin（种子权限已提交）或服务层直测；此坑记录避免 M5 重踩

## 5. 已知边界

- options 返回全量启用行（一期档案规模小）；超过 ~2000 行时需服务端搜索式下拉（M7 评估）。
- 登录即可意味着任何认证用户可见档案名称集合——按一期威胁模型接受（内部 ERP，名称非机密）；如需收紧，改为按"可见模块"推导是 M6+ 的事。
- 前端下拉不再受 pageSize:200 截断影响（options 无分页）。
