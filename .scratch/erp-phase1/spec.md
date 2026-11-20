# 一期规格：基础资料 + 采购 + 销售 + 库存（闭环）

Status: ready-for-agent

**范围**：基础资料 + 采购 + 销售 + 库存。二期财务、三期分析仅做字段/事件预留。
**验收标准**：跑通一笔从采购入库到销售出库的完整业务（含批次/SN 追溯、锁定、分批履约、盘点调拨、作废冲正），四层库存不变量全程零差异。
**约束**：单租户、模块化单体、MySQL 8 + MyBatis-Plus + Flyway、Sa-Token、EasyExcel、Vue 3 + Element Plus 独立 frontend/、Docker Compose 部署、TDD。一切从简。
**术语**：以根目录 `GLOSSARY.md` 为准；关键决策见 `docs/adr/0001..0005`。

## 核心模型决策摘要

| # | 决策点 | 结论 | ADR |
|---|--------|------|-----|
| 1 | 均价粒度 | 仓库 × SKU（批次/SN 不参与计价） | 0001 |
| 2 | 库存存储 | 双层库存行（主行管钱和量，批次行只管量）+ 金额权威、单价派生、出清归零 | 0002 |
| 3 | 冲正策略 | 库存行 = 流水重放终态；作废按原流水快照金额原路冲回；逆序作废 | 0003 |
| 4 | 锁定结构 | 汇总（inv_stock.locked_qty）+ 明细（inv_stock_lock）双层，不变量对齐 | 0002 |
| 5 | 盘点锁 | 无物理锁表，"盘点中"状态的盘点单本身即范围锁 | 0004 |
| 6 | SN 存储 | 全局 SN 主表 + 单据行 sn_list JSON，不建每单 SN 子表 | 0002 |
| 7 | 并发控制 | 库存行 SELECT...FOR UPDATE（固定顺序加锁）+ 单据状态条件 UPDATE，不用 @Version 乐观锁 | 0005 |
| 8 | 认证 | Sa-Token 内存模式（无 Redis）+ 注解权限点；仓库数据权限 Service 层显式过滤 | — |

## 1. Maven 包结构与模块调用规则

单 Maven module，包级模块化 + ArchUnit 守护依赖方向。

```
com.wille.erp
├── ErpApplication
├── common                  # 通用基建，不依赖任何业务包
│   ├── api                 # R<T>、PageQuery、PageResult、ErrorCode 常量
│   ├── exception           # BizException、GlobalExceptionHandler
│   ├── config              # MybatisPlusConfig(分页/字段自动填充)、SaTokenConfig、JacksonConfig、OpenApiConfig
│   ├── entity              # BaseEntity（公共字段）
│   ├── enums               # DocStatus、DocType、BizType、MgmtMode、SnStatus 等
│   ├── event               # 单据事件基类（Spring ApplicationEvent，二期财务钩子）
│   └── util
├── system                  # 用户/角色/权限/用户仓库/系统参数/编号规则/登录
│   ├── api                 # CurrentUserHolder（当前用户+可见仓库）、DocNoService、ConfigService
│   └── controller / service / mapper / entity / dto
├── basedata                # 商品(含管理维度)/类别/客户/供应商/仓库/批次主档
│   ├── api                 # ProductReader（管理维度、保质期）、WarehouseReader
│   └── controller / service / mapper / entity / dto
├── inventory               # 库存核心
│   ├── api                 # 唯一门面 StockService（in/out/lock/unlock/release/available/查询）+ Cmd/DTO
│   ├── core                # 均价计算器（纯函数）、流水重放对账器
│   └── controller / service / mapper / entity / dto
├── purchase                # 询价单/采购订单/采购入库单/采购退货单
└── sale                    # 报价单/销售订单/销售出库单/销售退货单
```

**调用规则**（ArchUnit 固化约 6 条，构建必跑）：

| 规则 | 说明 |
|---|---|
| 允许 | purchase / sale / inventory → `basedata.api`、`system.api`、`common` |
| 允许 | purchase / sale → `inventory.api`（只能通过 StockService 门面动库存） |
| 允许 | inventory → `basedata.api`（读管理维度/保质期） |
| 禁止 | basedata、common、system → 任何单据业务包 |
| 禁止 | inventory → purchase / sale（库存不知道单据语义，只接受 Cmd） |
| 禁止 | purchase ↔ sale 互相依赖 |
| 禁止 | 任何模块 import 其他模块的 mapper / entity（跨模块直接查库） |

跨模块状态联动由发起方模块在自己事务里调对方 api 完成；二期财务通知走 Spring 进程内事件（`SaleOutboundAuditedEvent` 等，一期只发布、无监听者）。

## 2. 数据库设计（38 张表）

### 2.0 全局约定

**公共字段**（所有表，MetaObjectHandler 自动填充，下文不再重复）：
`id BIGINT UNSIGNED AUTO_INCREMENT`（选自增不选雪花：单实例、避免 JS 精度问题）、`create_by/update_by BIGINT`、`create_time/update_time DATETIME`、`deleted TINYINT NOT NULL DEFAULT 0`（@TableLogic；已审核单据只作废不删除）。

**单据头公共业务字段**（11 种单据头统一）：
`doc_no VARCHAR(32) UNIQUE NOT NULL`、`status TINYINT NOT NULL`（10 草稿/15 盘点中/20 已审核/30 已完成/40 已关闭/50 已作废，留间隔）、`doc_date DATE NOT NULL`、`currency CHAR(3) NOT NULL DEFAULT 'CNY'`（预埋，恒 CNY）、`remark VARCHAR(255)`、`audit_by/audit_time`、`void_by/void_time/void_reason VARCHAR(200)`（作废留痕，原因必填）、`fin_flag TINYINT NOT NULL DEFAULT 0`（二期预留：0 未生成财务单据/1 已生成）。

**单据行公共字段**：`doc_id BIGINT NOT NULL`（索引）、`line_no INT NOT NULL`、`sku_id BIGINT NOT NULL`、`sku_name/spec/unit VARCHAR`（下单时快照冗余，保历史与打印保真）、`qty DECIMAL(18,4) NOT NULL`、`price DECIMAL(18,6)`、`amount DECIMAL(18,2)`、`remark VARCHAR(255)`。

**精度**：qty DECIMAL(18,4)、price DECIMAL(18,6)、amount DECIMAL(18,2)、均价/成本价 DECIMAL(18,6)、成本金额 DECIMAL(18,2)；舍入一律 HALF_UP。业务校验在应用层，DB 只兜唯一键/非空。

**多态引用**：流水/SN 表中 `biz_doc_type` 等用 VARCHAR(30) 存枚举名（`PUR_INBOUND/PUR_RETURN/SAL_OUTBOUND/SAL_RETURN/TRANSFER/COUNT/OPENING`）。

**含税口径**：全部含税价、不做价税分离，行无税率字段；二期发票登记需要时 Flyway V3 补列。

### 2.1 system（7 张）

| 表 | 用途 | 关键字段与约束 |
|---|---|---|
| sys_user | 用户 | username UNIQUE NOT NULL；password（BCrypt）；real_name；phone；status(1 启用/0 停用)；last_login_time |
| sys_role | 角色 | role_code UNIQUE NOT NULL；role_name；built_in TINYINT（预置五角色不可删） |
| sys_permission | 权限点（菜单+按钮合一） | perm_code UNIQUE NOT NULL（如 `purchase:order:audit`、`inventory:cost:view`）；perm_name；type(MENU/BUTTON)；parent_id 树；route_path/icon/sort_no。前端路由静态定义，按登录返回的 perm_code 集合过滤菜单和按钮 |
| sys_user_role | 用户-角色 | UNIQUE(user_id, role_id) |
| sys_role_permission | 角色-权限 | UNIQUE(role_id, permission_id) |
| sys_user_warehouse | 用户可见仓库 | UNIQUE(user_id, warehouse_id)；warehouse_id=0 表示全部仓库（含未来新增）；admin 视为全仓库；无绑定 → 看不到任何库存数据 |
| sys_config | 系统参数 | param_key UNIQUE（`stock.allow.negative`=false、`purchase.over.receive.ratio`=0…）；param_value VARCHAR；value_type(BOOL/DECIMAL/INT/STRING)；param_name。ConfigService 进程内缓存（ConcurrentHashMap，更新刷新，无 Redis） |
| sys_doc_no_rule | 单据编号规则 | doc_type UNIQUE；prefix（CGDD/CGRK/XSDD/XSCK/DB/PD/QC…）；date_pattern；seq_length(默认 4)；seq_scope(DAY/MONTH/NONE)；current_seq、current_period。取号 = 单事务内该行 FOR UPDATE + 自增 + 期间变更重置；作废不回收号 |

（注：sys 实为 8 行，含编号规则表；总表数以 DDL 为准。）

### 2.2 basedata（5 张）

| 表 | 用途 | 关键字段与约束 |
|---|---|---|
| base_category | 商品类别 | name；parent_id；sort_no |
| base_product | 商品（一行 = 一个 SKU） | sku_code UNIQUE NOT NULL；name NOT NULL；spec；unit VARCHAR(20) NOT NULL（单一计量单位）；barcode；category_id；**mgmt_mode TINYINT NOT NULL（1 不管/2 管批次/3 管 SN/4 批次+SN）**；shelf_life_days INT（管批次必填）；**expire_warn_days INT（临期预警天数，一期预埋不计算）**；ref_sale_price DECIMAL(18,6) 选填；status；remark。约束：已有库存/流水/单据引用后 mgmt_mode 禁改（应用层） |
| base_customer | 客户 | code UNIQUE；name NOT NULL；contact；phone；address；tax_no（二期预埋）；status |
| base_supplier | 供应商 | 同客户结构 |
| base_warehouse | 仓库（≤10） | code UNIQUE；name NOT NULL；address；keeper；status；sort_no |

### 2.3 inventory（10 张）

| 表 | 用途 | 关键字段与约束 |
|---|---|---|
| inv_lot | 批次主档（全局，不按仓库） | UNIQUE(sku_id, lot_no)；production_date；expiry_date（=生产日期+保质期，冗余可手改，三期临期直接查此列）；supplier_id 可空。同 SKU 同批号跨供应商视为同一批次复用 |
| inv_stock | **库存主行（计价+可用量权威），仓库×SKU** | UNIQUE(warehouse_id, sku_id)；qty DECIMAL(18,4) DEFAULT 0；locked_qty DEFAULT 0；cost_price DECIMAL(18,6)；cost_amount DECIMAL(18,2)。可用量 = qty − locked_qty（派生不落库）。行不存在 ≡ 全 0，首次变动 upsert |
| inv_lot_stock | 批次库存行（只记数量），仓库×SKU×批次 | UNIQUE(warehouse_id, sku_id, lot_id)；qty。不变量：管批次 SKU 的 SUM(批次行.qty) = 主行.qty |
| inv_sn | SN 主档（一行一物） | UNIQUE(sku_id, sn_code)；lot_id（批次+SN 必填，纯 SN 为空）；status TINYINT（1 IN_STOCK/2 OUT）；warehouse_id（在库有值，出库 NULL）；in_doc_type/in_doc_id/in_doc_no/in_time；out_doc_type/out_doc_id/out_doc_no/out_time。不变量：管 SN SKU 的 COUNT(status=1 且 warehouse=X) = 主行.qty |
| inv_stock_lock | **订单锁定明细**（销售订单行 → 一行） | lock_doc_type（一期恒 SALE_ORDER）；lock_doc_id；lock_item_id；warehouse_id；sku_id；lock_qty（原始）；remain_qty（剩余未核销）；INDEX(lock_doc_type, lock_doc_id)、INDEX(warehouse_id, sku_id)。remain_qty=0 保留作历史。不变量：locked_qty = SUM(remain_qty) GROUP BY warehouse, sku |
| inv_flow | **库存流水（insert-only，永不改删）** | 见 §3f。INDEX(warehouse_id, sku_id, create_time)、INDEX(biz_doc_type, biz_doc_id)、INDEX(lot_id) |
| inv_opening / inv_opening_item | 期初库存单（头/行），走统一状态机 | 头：warehouse_id NOT NULL（一仓一期初单）。行：sku_id；lot_id/lot_no/production_date（管批次必填）；qty；**cost_price NOT NULL（期初均价，Excel 给定，允许 0）**；cost_amount；sn_list JSON TEXT（管 SN 必填，JacksonTypeHandler）。约束（应用层）：同仓库×SKU 只允许一张非作废期初行；审核后该 SKU 有后续流水则禁作废 |
| inv_transfer / inv_transfer_item | 调拨单（一步式，头/行） | 头：out_warehouse_id、in_warehouse_id NOT NULL（应用层校验不相等）。行：sku_id；lot_id（管批次必填，批次号不变随单平移）；qty；sn_list JSON（管 SN 必填）；cost_amount（= qty × 调出仓均价，审核后回填） |
| inv_count / inv_count_item | 盘点单（头/行） | 头：warehouse_id NOT NULL；scope_desc VARCHAR；status 用 15=COUNTING 中间态。行：sku_id；lot_id（管批次按批次行盘点）；book_qty（开始盘点时快照，此后不变）；count_qty（COUNTING 期间可反复录入）；diff_qty（=count−book，审核时定格）。审核按差异直接调库存写流水（biz_type=COUNT_GAIN_IN/COUNT_LOSS_OUT），**不生成独立调整单** |

### 2.4 purchase（8 张）

| 表 | 用途 | 关键字段与约束 |
|---|---|---|
| pur_inquiry / pur_inquiry_item | 询价单：**每行 = 一个 SKU×供应商报价**，不动库存财务 | 头：title；inquiry_date。行：sku_id；supplier_id NOT NULL；qty（询价量）；price（报价）；lead_time_days；selected TINYINT（比价选中）。转订单：选中行按供应商分组，每组生成一张采购订单草稿 |
| pur_order / pur_order_item | 采购订单 | 头：supplier_id NOT NULL；warehouse_id NOT NULL（默认入库仓）；expect_date。行：sku_id；qty；price；amount；**done_qty DEFAULT 0（累计已入库）**；line_status（10 执行中/30 已完成/40 已关闭）。溢收校验：done_qty ≤ qty×(1+`purchase.over.receive.ratio`)；done_qty ≥ qty 自动行完成；全部行终态 → 头 COMPLETED |
| pur_inbound / pur_inbound_item | 采购入库单（审核动库存、重算均价） | 头：supplier_id；warehouse_id NOT NULL；source_order_id NULL（支持无订单直接入库）。行：source_order_item_id NULL；qty（本次实收）；**price 默认带订单价、允许修改（实际结算价，影响均价与二期应付）**；amount；lot_id/lot_no/production_date（管批次必填，审核时物化 inv_lot）；sn_list JSON（管 SN 必填，数量=qty） |
| pur_return / pur_return_item | 采购退货单（出库方向） | 头：supplier_id；warehouse_id；source_inbound_id NULL（NULL=无原单退货）。行：source_inbound_item_id NULL；qty（有原单时：同原单行累计退货 ≤ 原入库 qty）；price（有原单=原单价不可改；无原单手录）；lot_id；sn_list JSON；reason NOT NULL。成本一律按当前均价结转 |

### 2.5 sale（8 张）

| 表 | 用途 | 关键字段与约束 |
|---|---|---|
| sal_quote / sal_quote_item | 销售报价单（**不锁库存**） | 头：customer_id；valid_until DATE。行：sku_id；qty；price；amount。转销售订单后头置 COMPLETED |
| sal_order / sal_order_item | 销售订单（**审核即锁库存**） | 头：customer_id NOT NULL；warehouse_id NOT NULL；expect_date。行：sku_id；qty；price；amount；done_qty（累计已出库）；line_status。审核时按仓库×SKU 生成 inv_stock_lock 明细并汇总到 locked_qty |
| sal_outbound / sal_outbound_item | 销售出库单（出库即发货） | 头：customer_id；warehouse_id NOT NULL；source_order_id NULL（支持直接出库）；**carrier VARCHAR(50)（可空=自提）**；**tracking_no VARCHAR(50)**。行：source_order_item_id NULL；qty；price（默认订单价，可改）；amount；lot_id；sn_list JSON；**硬校验：source 存在时 warehouse_id = 订单 warehouse_id，且 qty ≤ 订单行剩余量（qty − done_qty）——销售超发严格禁止** |
| sal_return / sal_return_item | 销售退货单（入库方向） | 头：customer_id；warehouse_id（退货入库仓）；**source_outbound_id NULL（用户裁决：允许无原单退货）**。行：source_outbound_item_id NULL；qty（有原单时：同原单行累计退货 ≤ 原出库 qty）；price（有原单=原单价不可改；**无原单手录**）；lot_id；sn_list JSON（有原单：从原出库行 SN 中勾选 status=OUT 且未被退过的）；reason NOT NULL。成本按当前均价入账 |

合计 38 张表（含 base_category）。Flyway：`V1__schema.sql`（全部 DDL）+ `V2__init_data.sql`（admin、五预置角色、权限点种子、默认参数、编号规则、admin 仓库绑定 warehouse_id=0）。

## 3. 关键模型规则

### 3a. 均价粒度与双层库存行（ADR-0001/0002）

均价粒度 = **仓库×SKU，不含批次**（按批次算 = 批次个别计价法，违背"批次不参与计价"决策；国内主流分仓移动平均同此粒度）。

**金额权威、单价派生、出清归零**：
- 入库（采购入库/退货入库/盘盈/调拨入/期初）：`cost_amount += round(qty_in × price_in, 2)`；`qty += qty_in`；`cost_price = round(cost_amount / qty, 6)`
- 出库（销售出库/采购退货/盘亏/调拨出）：`结转额 = round(qty_out × cost_price, 2)`；**若 qty_out = qty（全部出清）则结转额 = cost_amount 全额**（吃掉尾差不留残值）；`cost_amount -= 结转额`；`qty -= qty_out`；`cost_price = qty > 0 ? round(cost_amount/qty, 6) : 0`
- 数学性质：按均价出库不改变 cost_price；只有入库价 ≠ 当前均价才重算均价
- 不变量（测试与对账器断言）：管批次 SUM(批次行.qty) = 主行.qty；管 SN 在库 SN 数 = 主行.qty；cost_amount ≈ qty × cost_price（6 位舍入内）

### 3b. SN 生命周期

两态 `IN_STOCK(1) ⇄ OUT(2)`，状态住 inv_sn 主表；与单据关联 = 主表 in_doc_*/out_doc_*（最近一次）+ 各单据行 sn_list JSON（永久留痕）。

| 阶段 | 动作 |
|---|---|
| 入库登记 | 草稿态前端录入/扫码 sn_list（行 JSON）；**审核时**逐个物化：UNIQUE(sku_id, sn_code) 冲突检查——不存在则 INSERT（IN_STOCK, warehouse=入库仓, lot=行批次）；已存在且 OUT → 退货回库，翻回 IN_STOCK 更新仓库/批次/in_doc；已存在且 IN_STOCK → 报错"SN 重复在库" |
| 在库 | warehouse_id 有值；调拨审核只更新 warehouse_id（批次不变） |
| 出库核销 | 销售出库/采购退货审核：校验每个 SN IN_STOCK 且 warehouse/批次一致 → 置 OUT、warehouse_id=NULL、写 out_doc_* |
| 退货回库 | 销售退货审核：勾选的 sn_list 置回 IN_STOCK、warehouse=退货入库仓、写 in_doc=退货单 |

不建 SN 独立轨迹表——多轮往返历史由单据行 sn_list + inv_flow 拼出；三期若查询不便再补 insert-only `inv_sn_log`（成本极低）。

### 3c. 作废冲正（ADR-0003）

**总策略：库存行永远等于流水重放的终态。** 逆向操作金额一律取原流水快照（cost_price/cost_amount/balance_*），绝不反算、绝不用当前均价冲历史 → 任意合法作废顺序下均价自洽，无漂移累积。

1. **采购退货（正常出库）**：按当前均价结转成本（均价粒度仓库×SKU，"退货导致均价回退"是伪命题——出库不改变均价）。退货价（原单价/手录价）只影响二期应付：流水同时记 price（业务价）与 cost_price（计价价），两线分离。
2. **作废入库类**（采购入库/期初/退货入库/盘盈/调拨入）：按原流水金额原路冲减。前置校验：冲减后 qty ≥ 0 且 qty − locked_qty ≥ 0，否则拒绝并提示"库存已被后续单据消耗，请先作废下游单据"（**逆序作废**，不自动级联）。
3. **作废出库类**（销售出库/采购退货/盘亏/调拨出）：按原结转成本价回冲（qty +=、cost_amount += 原结转额）。即使作废前有新入库（均价已变），回冲后恰好等于"该笔出库从未发生"的移动平均——总额/总量守恒，无漂移。
4. **作废后重审核**：按当时均价重新结转、写新流水；与原次成本不同属业务事实，接受，不补偿。

配套：`inventory/core` 对账器（从 inv_flow 重放，与 inv_stock diff），暴露管理端接口 + 测试基建。调拨作废 = 调出仓按原结转额回冲 + 调入仓按原额冲减（受第 2 条约束）。

### 3d. 盘点锁（ADR-0004）

**无物理锁表**："锁" = 处于 COUNTING(15) 状态的盘点单，范围 = 头.warehouse_id + 行 sku_id 集合（生成时定格）。

- 其它单据审核时校验（入库/出库/调拨/期初/销售订单审核）：`EXISTS (SELECT 1 FROM inv_count c JOIN inv_count_item i ON i.doc_id=c.id WHERE c.status=15 AND c.deleted=0 AND c.warehouse_id=? AND i.sku_id IN (...))` 命中即拒绝。并发 ≤50、盘点单极少，成本可忽略。
- 防重叠盘点：开始盘点时校验范围内无其它 COUNTING 单（仓库相同且 SKU 集相交即拒绝）。
- 与订单锁定区分：盘点锁 = 范围级**硬冻结**（禁一切出入库审核，不存储、由状态派生）；订单锁定 = 数量级**软占用**（只减可用量不阻入库，汇总+明细存储）。
- 盘点快照 book_qty 取实际 qty（**不减 locked_qty**）；盘亏后可能 qty < locked_qty（可用量负），仅前端黄条预警"存在未履约订单锁定"，不阻断盘点。

### 3e. 订单锁定结构与生命周期

汇总（inv_stock.locked_qty）+ 明细（inv_stock_lock，订单行 → 一行，remain_qty 剩余）双层；不变量 `locked_qty = SUM(remain_qty)` 同事务维护、对账器断言。

1. **订单审核**：逐行校验可用量（`stock.allow.negative`=false 时 qty − locked_qty ≥ 需求，否则跳过允许 locked 超 qty）→ INSERT inv_stock_lock(lock_qty=remain_qty=行 qty) → `UPDATE inv_stock SET locked_qty = locked_qty + ?`（行级 FOR UPDATE）。任一行不足 → 整单审核失败回滚（不做部分锁定）。
2. **部分出库（锁定转实扣）**：出库行必带 source_order_item_id；审核事务内：实扣量 = 本次 qty（已约束 ≤ 剩余量，锁定必然够）→ remain_qty −= qty、locked_qty −= qty、inv_stock 按均价出库规则扣减、订单行 done_qty += qty。锁定转实扣 = locked 减、qty 减同时同额发生，可用量瞬间不变（可用量早在订单审核时已扣掉）。
3. **行手动关闭**：line_status=40，locked_qty −= remain_qty、remain_qty 置 0；全部行终态 → 头 COMPLETED/CLOSED。
4. **订单作废**：前提无未作废下游出库单；按明细全额释放锁定（订单本身没动过 qty）。
5. **直接出库（无 source）**：不动锁定，按可用量校验后直接扣 qty。

调拨、采购订单、询价/报价单均不产生锁定。

### 3f. 库存流水字段（inv_flow，insert-only）

| 字段组 | 字段 | 支撑 |
|---|---|---|
| 定位 | warehouse_id、sku_id、lot_id(可空) | 三期按批次重放做 FIFO 式追溯 |
| 变动 | qty DECIMAL(18,4) **带符号**（入+/出−）、price DECIMAL(18,6) NULL（业务价，二期财务用）、cost_price DECIMAL(18,6)（本次结转/入账成本单价快照）、cost_amount DECIMAL(18,2) **带符号** | price 与 cost 两线分离 → 三期毛利 = Σ(price×qty) − Σ(cost_amount)（出库行）；冲正取 cost 快照 |
| 余额快照 | balance_qty、balance_cost_amount、balance_cost_price（变动后终态） | 任意时点成本直接可查；对账器 O(n) 校验"每行余额 = 上行余额 + 本行变动" |
| 溯源 | biz_type（OPENING/PUR_IN/PUR_RETURN_OUT/SALE_OUT/SAL_RETURN_IN/TRANSFER_OUT/TRANSFER_IN/COUNT_GAIN_IN/COUNT_LOSS_OUT）、biz_doc_type/biz_doc_id/biz_doc_no/biz_doc_item_id | 单据↔流水双向追溯 |
| 冲正配对 | reverse_flow_id NULL（冲正流水指向原流水） | 防重复冲正 |
| 公共 | create_time/create_by（=审核人审核时刻） | — |

SN 明细不进流水（在单据行 sn_list + inv_sn 状态里），避免流水表爆炸。

## 4. 核心时序

### 4.1 销售出库单审核（单事务）

1. 鉴权 `@SaCheckPermission("sale:outbound:audit")` + 数据权限（单据仓库 ∈ 可见仓库）
2. **条件状态更新** `UPDATE sal_outbound SET status=20 WHERE id=? AND status=10`，影响 0 行 → 抛"单据状态已变化"（防双击，天然幂等）
3. 业务校验：行非空、qty>0；管批次行批次必填且批次库存充足；管 SN 行 sn_list 数量=qty 且逐个 IN_STOCK/仓库批次一致；有 source：仓库一致、qty ≤ 订单行剩余量；盘点锁 EXISTS 校验
4. 可用量校验（仅无 source 部分）：读 `stock.allow.negative`，false 时逐行 qty − locked_qty ≥ 出库量
5. `StockService.outbound(cmd)`：**按 (warehouse_id, sku_id) 升序**逐行 SELECT...FOR UPDATE inv_stock（固定顺序防死锁）→ §3a 出库规则扣减 → 扣 inv_lot_stock → 翻转 inv_sn 为 OUT
6. 锁定核销（有 source 行）：remain_qty −=、locked_qty −=；订单行 done_qty +=，done_qty ≥ qty → 行 COMPLETED，全部行终态 → 头 COMPLETED
7. 写 inv_flow（每行一条，含快照，biz_type=SALE_OUT）
8. 收尾：audit_by/audit_time；fin_flag 保持 0；发布 `SaleOutboundAuditedEvent`（二期应收钩子，一期无监听者）
9. 提交；任何异常整体回滚（编号在草稿创建时已取，审核不取号）

### 4.2 单据作废冲正（单事务）

1. 鉴权 + 作废原因必填
2. **下游校验**：入库单——存在引用它的非作废退货单 → 拒绝；订单类——存在非作废下游出/入库单 → 拒绝（提示逆序作废）；期初单——该仓库×SKU 已有后续流水 → 拒绝
3. **数量校验（仅入库类）**：每行 qty − locked_qty ≥ 原入库量，不足 → 拒绝。出库类回冲是加库存，免校验
4. 条件状态更新 → 50，影响 0 行报并发冲突
5. 库存冲正：取本单全部未被冲正的 inv_flow 行，按原 cost_amount 快照反向执行；批次行回冲原 lot_id；SN 逐个翻转（入库类作废：删除本次新建的 SN / 将本次翻 OUT 的 SN 置回 IN_STOCK，以 in_doc/out_doc 指向本单为判定）
6. 锁定冲正（销售订单/出库单）：按 inv_stock_lock 明细恢复，订单行 done_qty 回减、状态回退 AUDITED
7. 写冲正流水：方向相反、金额取原快照、reverse_flow_id=原流水 id、remark="作废冲正"
8. void_by/void_time/void_reason 留痕；发布 XxxVoidedEvent（二期红冲钩子）
9. 提交；对账器可事后验证

### 4.3 盘点单生命周期

1. **创建（DRAFT）**：选仓库+范围（全部/按类别/指定 SKU）→ 展开生成行（管批次按 inv_lot_stock 逐批次行、非批次按 SKU 行），book_qty 暂空，可增删行
2. **开始盘点（DRAFT→COUNTING，事务）**：校验无重叠 COUNTING 单 → 逐行 book_qty = 当前 qty 快照 → 盘点锁自此生效
3. **录入实盘（COUNTING 可反复）**：逐行录 count_qty 或 Excel 实盘导入回填；diff 实时展示；可"取消盘点"→ 回 DRAFT（清快照、解锁）
4. **审核（COUNTING→COMPLETED，事务）**：diff ≠ 0 的行逐行调整——盘盈 in(diff, price=当前 cost_price)（量额同增均价不变）、盘亏 out(diff, 按当前均价结转)；批次行同步；写流水（COUNT_GAIN_IN/COUNT_LOSS_OUT）。SN 商品仅按数量差调整（明细差异走 SN 人工调整页）。审核完成即 COMPLETED，锁自动解除；盘亏致 locked_qty > qty 时返回体带预警
5. **作废（COMPLETED→VOIDED）**：按 §4.2 冲正（盘盈冲正受数量校验约束）

## 5. API 约定

**响应体**：`{ "code": 0, "msg": "ok", "data": {...} }`。code=0 成功；错误码分段：1xxxx 通用/认证（10001 参数校验、10002 并发冲突）、2xxxx 基础资料、3xxxx 库存（30001 可用量不足、30002 盘点锁冲突、30003 作废冲正库存不足、30004 SN 状态不符）、4xxxx 采购（40001 超溢收）、5xxxx 销售（50001 超订单剩余量）、6xxxx 系统（60001 取号失败）。HTTP 语义：401 未登录、403 无权限，**其余一律 HTTP 200 + 业务 code**（前端 axios 拦截器统一 toast）。GlobalExceptionHandler：BizException→code/msg；参数校验→10001+首条字段错误；兜底→500 码+日志（不泄露堆栈）。金额/数量/id 以 JSON number 返回（精度安全界内）。

**分页**：pageNum（默认 1）、pageSize（默认 20 上限 200）+ 过滤字段平铺 query string；响应 `data: { total, records }`；排序固定 create_time DESC（库存查询按 sku_code），不开放通用 sort。所有库存/单据查询自动拼仓库数据权限（common 提供 WarehouseScopeHelper，Service 层显式调用，不用 MyBatis 拦截器魔法）。

**REST 风格**：`/api/{module}/{resources}` 复数 kebab-case；状态流转子资源动词；代表端点：

| 模块 | 端点 |
|---|---|
| 认证 | `POST /api/auth/login`、`/logout`、`GET /api/auth/me`（用户+权限码+可见仓库） |
| 系统 | `/api/system/users`、`/roles`（+`PUT /{id}/permissions`）、`/configs`、`/doc-no-rules` |
| 基础资料 | `/api/basedata/products`（CRUD + `POST /import` Excel 返回行级错误报告 + `GET /export`）、`/customers`、`/suppliers`、`/warehouses` 同构 |
| 库存 | `GET /api/inventory/stocks`（汇总+可用量+均价，成本列按 `inventory:cost:view` 置 null）、`/stocks/lots`、`/sns`（+`POST /sns/{id}/adjust` 人工调整留痕）、`/flows`、`GET /flows/replay-check`（对账） |
| 采购 | `/api/purchase/inquiries`（+audit、`POST /{id}/convert-to-orders` 按供应商分组转草稿）、`/orders`（+audit/void、`/{id}/items/{itemId}/close`、`POST /{id}/create-inbound` 带出未履约行）、`/inbounds`（+audit/void）、`/returns`（+`POST /api/purchase/inbounds/{id}/create-return`） |
| 销售 | `/api/sale/quotes`（+`/{id}/convert-to-order`）、`/orders`（+audit/void/close、`/{id}/create-outbound`）、`/outbounds`（+audit/void）、`/returns`（+`POST /api/sale/outbounds/{id}/create-return`、`GET /api/sale/outbound-items/{itemId}/returnable-sns`） |
| 调拨/盘点/期初 | `/api/inventory/transfers`（+audit/void）、`/counts`（+`/{id}/start`、`PUT /{id}/items/{itemId}/count`、`POST /{id}/import-counts`、`/{id}/audit`、`/{id}/cancel`、`/{id}/void`）、`/openings`（+`POST /import`、audit/void） |
| 编号 | 草稿创建时服务端自动取号返回，无独立端点 |

## 6. 里程碑与测试策略（TDD）

**测试基建（M0 建好全程复用）**：`application-test.yml` 指向本机 MySQL 独立库 `erp_test`（Flyway 自动迁移；服务层测试 @SpringBootTest + @Transactional 回滚；不引 Testcontainers）；test 包 DataFactory（一行造商品/仓库/用户/参数）；**对账器断言工具**（任意测试收尾调用：重放 inv_flow 比对 inv_stock/inv_lot_stock/inv_sn/inv_stock_lock 四层不变量）。TDD 节奏：Service 接口+测试（红）→ 实现（绿）→ 重构；Controller 用 MockMvc 薄测（参数/权限/响应体），业务正确性全靠 Service 测试。

| # | 里程碑 | 内容与依赖 | 验收点 | 先写的测试 |
|---|--------|-----------|--------|-----------|
| M0 | 脚手架与工程基线 | pom 增补（mybatis-plus-spring-boot3-starter 3.5.x、mysql-connector-j、flyway-core+flyway-mysql、sa-token-spring-boot3-starter、easyexcel 4.x、validation、springdoc-openapi、archunit-junit5(test)）；common 全套；Flyway V1+V2；docker-compose.yml（healthcheck/TZ/utf8mb4/nginx 反代）；frontend 脚手架（Vite+Vue3+TS+ElementPlus+Pinia+router+axios 封装+dev proxy）；ArchUnit 规则。**依赖：无** | compose 三服务健康；迁移成功；ArchUnit 全绿 | context loads；R 序列化；异常处理器各分支；ArchUnit 6 条 |
| M1 | 认证与系统管理 | 登录/登出/me（BCrypt）；用户/角色/权限 CRUD+授权树；仓库绑定；系统参数（缓存刷新）；取号服务（行锁/期间重置）；前端登录页+主框架+路由守卫+`v-perm` 指令+系统管理 4 页。**依赖 M0** | 五角色权限码正确；绑仓库 A 的用户 me 只含 A；50 线程并发取号无重号 | AuthService（密码错/停用/权限聚合/仓库范围）；DocNoService 并发取号；ConfigService 类型转换与刷新 |
| M2 | 基础资料+Excel | 商品（四模式）/类别/客户/供应商/仓库 CRUD、启停用、逻辑删；三类 Excel 导入（全量校验、有错不落库、行级错误报告）+导出；前端 5 页。**依赖 M1** | 1000 行含错导入→精确行号报告；有引用商品改 mgmt_mode 被拒 | ProductService（编码唯一/模式变更约束/删除引用校验）；ImportService（真实 xlsx fixture：全对/含错/重复编码/模式非法） |
| M3 | **库存核心+期初（地基，测试最重）** | 五件套表；StockService（in/out/lock/unlock/release/available，均价算法、FOR UPDATE 顺序加锁、负库存参数、流水+快照、SN 物化/翻转）；对账器+replay-check；期初单 CRUD+Excel 导入（批次/SN/期初价）+审核/作废；库存查询页（汇总→批次→SN 三级下钻）+流水页+期初页。**依赖 M2** | 期初导入审核后三层正确；replay-check 零差异；均价手算样例（入10@10→入5@13→出8：均价11、结转88、余7@11额77） | 均价计算器纯函数单测（含出清尾差归零）；StockService 集成测试族——**随机操作序列 property 测试**（任意 in/out/lock 序列断言四层不变量+重放一致）；可用量/负库存开关矩阵；SN 重复入库/核销/回库；期初重复导入拒绝 |
| M4 | 采购链路 | 状态机通用支撑（条件更新/作废框架/审计字段）；询价（比价透视、分组转单）；采购订单（溢收、done_qty、行关闭、自动完成）；采购入库（源单带出/直接入库、批次物化、SN、价格可改、审核调 StockService.in、作废冲正）；采购退货（有/无原单、量校验、SN 勾选）；前端 4 组页面。**依赖 M3** | 3 供应商比价转 2 张订单；溢收 10% 边界；入库审核均价联动；作废被消耗时拒绝 | 状态机转移矩阵；溢收边界（=、+ε、超比例）；分批 done_qty 与自动完成；退货超原量拒绝；作废逆序约束；每用例收尾对账断言 |
| M5 | 销售链路（**一期闭环达成**） | 报价（转订单）；销售订单（审核锁定、可用量校验、行关闭释放）；销售出库（锁定转实扣、直接出库、物流字段、SN 核销、事件发布）；销售退货（原单带出/无原单手录、SN 勾选回库）；可用量查询；前端 4 组页面。**依赖 M3（M4 可并行）** | 端到端：期初/采购入库→订单锁定→部分出库→完结→退货回库全跑通，四层不变量零差异 | 锁定生命周期测试族（每节点 locked_qty=SUM(remain) 断言）；超剩余量出库拒绝；直接出库可用量校验；退货 SN 状态机；**端到端闭环集成测试（验收标准的自动化版本）** |
| M6 | 盘点+调拨 | 调拨（一步式同事务、批次平移、SN 换仓、成本平移、作废双向冲正）；盘点（范围生成/start 快照+锁/实盘录入+Excel 导入/审核盘盈盘亏/cancel/void）；盘点锁 EXISTS 接入**所有**审核入口（回改 M3–M5）；SN 人工调整页；前端页面。**依赖 M4、M5** | 盘点期间购销调审核全被拒、完成后恢复；盘盈盘亏后 replay-check 零差异；调拨两仓量额守恒 | 盘点锁冲突矩阵（COUNTING × 7 种审核）；重叠盘点拒绝；快照后锁定边界；调拨原子性（人为异常断言两仓同时回滚）；盘亏负可用量预警路径 |
| M7 | 打印+导出+联调验收+部署 | 打印视图 `/print/:docType/:id`（无 Layout、@media print、固定模板、成本列按权限）；各列表 Excel 导出补齐；对照 §8 边界清单端到端手工联调；Linux Docker Compose 部署演练+mysqldump cron 备份脚本。**依赖 M6** | 11 种单据打印合格；生产演练环境完整验收业务；备份恢复演练通过 | 导出服务测试（写出字节可读回断言）；打印为手工验收项；部署 checklist |

## 7. 前端页面与路由

```
/login                          登录
/                               Layout（侧边菜单按权限过滤 + 顶栏用户/仓库切换）
├── /basedata/product           商品列表（左类别树+右列表，导入/导出/编辑弹窗）
├── /basedata/customer | supplier | warehouse   （弹窗编辑，导入/导出）
├── /purchase/inquiry(+edit/:id?)     询价（报价行+比价透视+选中转订单）
├── /purchase/order(+edit/:id?)       采购订单（履约进度列、行关闭、生成入库单入口）
├── /purchase/inbound(+edit/:id?)     采购入库（选源单带出、批次弹窗、SN 录入弹窗）
├── /purchase/return(+edit/:id?)      采购退货（选原入库单/无原单、SN 勾选）
├── /sale/quote(+edit/:id?)           销售报价
├── /sale/order(+edit/:id?)           销售订单（可用量提示、行关闭）
├── /sale/outbound(+edit/:id?)        销售出库（物流字段、批次/SN 选择、可用量实时提示）
├── /sale/return(+edit/:id?)          销售退货（原出库单 SN 勾选弹窗/无原单手录价）
├── /inventory/stock            库存查询（仓库×SKU 汇总→批次→SN 三级下钻，成本列权限控制）
├── /inventory/flow             库存流水（多条件+余额快照列）
├── /inventory/opening(+edit)   期初库存（Excel 导入向导+单据列表）
├── /inventory/transfer(+edit/:id?)   调拨单
├── /inventory/count(+edit/:id?)      盘点单（开始盘点/实盘录入/导入实盘/审核 分步操作）
├── /system/user | role | config | doc-no
└──（Layout 外）/print/:docType/:id   打印视图（11 种单据，独立空白布局）
```

工程约定：单据页公共组件 `DocumentPage`（头表单+可编辑明细表格+底部按钮条"保存草稿/提交审核/作废/关闭/打印"，按 status 驱动可见性）；`v-perm` 指令控按钮；axios 拦截器统一 code≠0 toast 与 401 跳登录；列表页 `useTable` composable（分页/查询/导出）。

## 8. 边界口径清单（已固化，含 4 项用户裁决）

| # | 问题 | 结论 |
|---|------|------|
| 1 | 报价单锁库存？ | 不锁（意向文档，锁了会因报价泛滥冻结可用量）；仅 valid_until 有效期，转订单才锁定 |
| 2 | 调拨走锁定？ | 不走。一步式同事务无锁定窗口；审核校验调出仓 qty − locked_qty ≥ 调拨量（调拨不吃订单占用） |
| 3 | SN 部分退货选具体 SN？ | `GET /api/sale/outbound-items/{itemId}/returnable-sns`：原出库行 sn_list 中 status=OUT 且未出现在任何非作废退货行的 SN，弹窗勾选，勾选数=退货 qty。多轮往返按最近一次出库单追溯（inv_sn.out_doc 指向最近） |
| 4 | 临期预警预埋？ | 预埋只存不算：shelf_life_days + expire_warn_days + inv_lot.production_date/expiry_date；三期一条 WHERE 查询零改造 |
| 5 | 销售超发？（**用户裁决**） | **严格禁止**：出库行 qty ≤ 订单行剩余量硬校验；超发击穿"锁定转实扣"不变量，确需多发走改单。溢收比例参数只作用于采购入库 |
| 6 | 无原单销售退货？（**用户裁决，覆盖详细设计初稿**） | **允许**：有原单价按原单不可改；无原单价格手录、成本按当前均价。采购销售对称。累计退货量校验仅在有原单时生效 |
| 7 | "已审核不可改" vs "手动关行"？ | 不矛盾：不可改指**业务要素**（商品/数量/价格/仓库）；关行/自动完成/作废属**状态迁移**，允许发生在 AUDITED 态。口径已入 GLOSSARY |
| 8 | 盘点"盘点中"装不进统一状态机？ | 状态枚举加 COUNTING(15) 中间态（DRAFT→COUNTING→COMPLETED，可 cancel 回 DRAFT、可 void）；枚举值留间隔（10/15/20/30/40/50）即为此 |
| 9 | SN 盘点差异定位？（**用户裁决**） | **从简**：盘点按数量差调整；SN 明细不齐走"SN 状态人工调整"页（必填原因、留痕）。SN 级扫码盘点二期以后 |
| 10 | 盘亏后 locked_qty > qty（可用量负）？ | 允许发生、仅预警不阻断（阻断则必须先解订单再盘点，流程过重）；负可用量出库本就受 stock.allow.negative 管辖，语义一致 |
| 11 | 入库价偏离订单价？ | 允许（默认带出可编辑）=实际结算价，均价与二期应付以其为准；差异留 remark。销售出库价同理 |
| 12 | 单据仓库与订单仓库不一致？ | 硬规则：带 source 的出/入库单仓库必须 = 订单仓库（审核校验）；分批入不同仓 = 拆两张订单。仓库在订单头不在行 |
| 13 | 无源直接出入库？ | 支持（source 可空）：零售/样品/借调等自用场景；校验可用量即可 |
| 14 | 税与币种？（**用户裁决**） | **含税价、不做价税分离**、恒 CNY；头预埋 currency 一列，行不预埋税率（二期 V3 迁移补） |
| 15 | mgmt_mode 变更边界？ | 已有库存/流水/单据引用后禁改（应用层+测试固化）；确需变更：库存清零或新建 SKU 重新期初 |
| 16 | EasyExcel 停更风险？ | 一期用 com.alibaba:easyexcel 4.x；导入导出收敛到各模块 XxxExcelService，将来换 FastExcel 只动一处 |
| 17 | 作废留痕完整性？ | 不建通用操作日志表：作废三件套（void_by/time/reason 必填）+ reverse_flow_id 配对流水已构成完整证据链；跳号正常，文档注明 |
| 18 | Sa-Token 内存模式代价？ | 无 Redis → 后端重启全员重登录；单实例自用可接受，写入部署文档；切勿因此引入 Redis |

## Critical Files

- `pom.xml`（M0 依赖增补落点）
- `src/main/resources/application.yml`（dev/test/prod 多环境）
- `src/main/java/com/wille/erp/ErpApplication.java`（@MapperScan 与扫描边界）
- `src/main/resources/db/migration/V1__schema.sql`（38 张表 DDL，§2 直接落地）
- `src/main/java/com/wille/erp/inventory/api/StockService.java`（全系统唯一库存变更门面，§3/§4 规则收口处）
