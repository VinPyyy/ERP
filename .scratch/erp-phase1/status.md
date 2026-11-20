# 一期进度状态（Phase 1 Status）

> 快照日期：2026-11-20。工单级明细见 `issues/`，过程裁决全录见 `.superpowers/sdd/01-m0-scaffold/progress.md`。

## 里程碑状态

| # | 里程碑 | 工单 | 状态 |
|---|--------|------|------|
| M0 | 脚手架与工程基线 | issues/01 | ✅ resolved |
| M1 | 认证与系统管理 | issues/02 | ✅ resolved |
| M2 | 基础资料 + Excel 导入 | issues/03 | ✅ resolved |
| M3 | 库存核心 + 期初（地基，测试最重） | issues/04 | ⏳ 未开始，下一个 |
| M4 | 采购链路 | issues/05 | 未开始（依赖 04） |
| M5 | 销售链路（一期闭环验收） | issues/06 | 未开始（依赖 04，可与 05 并行） |
| M6 | 盘点 + 调拨 | issues/07 | 未开始（依赖 05、06） |
| M7 | 打印 + 导出 + 联调验收 + 部署 | issues/08 | 未开始（依赖 07） |

## 验证基线（均为实际运行结果）

- `mvn test`：**86/86 通过**（common 10 + 认证/参数/编号/用户/角色服务 28 + Controller 薄测 12 + 基础资料服务/Excel 28 + contextLoads 1 + ArchUnit 7，含 50 线程并发取号、1000 行导入规模用例）
- `npm run build`：通过（vue-tsc 类型检查 + vite，1681+ modules）
- Flyway：`erp_dev`/`erp_test` 迁移至 v2——**41 张业务表**、112 权限点、11 编号规则、五预置角色
- MySQL：`erp-mysql` 容器（mysql:8.0 @3307）healthy，initdb 自动建双库

## 已落地能力清单

**工程基线（M0）**

- common：统一响应体 R / 分段错误码 / 分页 / 业务异常 + 全局异常处理（401/403 走 HTTP 语义，其余 200+code）/ BaseEntity（审计字段+逻辑删+remark）/ 8 个全局枚举 / 单据事件基类
- Flyway V1（41 表 DDL，utf8mb4_unicode_ci 兼容方言）+ V2 种子
- Docker Compose + 多阶段 Dockerfile ×2 + nginx 反代配置
- 前端脚手架：router 守卫 / auth store / axios 拦截器 / `v-perm` 指令 / BasicLayout 权限菜单 / useTable / DocumentPage 骨架
- ArchUnit 模块依赖守护（7 规则，构建必跑）

**认证与系统管理（M1）**

- Sa-Token 登录（BCrypt，admin/admin123 种子）+ 登出 + me 聚合（权限码并集/可见仓库）
- 用户（角色/仓库绑定全量替换、内置 admin 禁删）、角色（预置不可删、授权全量替换）、权限树、系统参数（进程内缓存即时生效）、单据编号（行锁取号 + 跨期重置 + 并发无重号）服务与 REST 端点全套
- 系统管理前端 4 页

**基础资料（M2）**

- 商品（四种管理维度 + 保质期/临期字段 + 有引用禁改模式经事件否决）/ 类别树（名称全局唯一）/ 客户 / 供应商 / 仓库 CRUD 全套端点与前端页
- EasyExcel 导入：全量校验、有错整批不落库、行号级错误清单（文件内重复/撞库/必填/模式非法/类别解析）；商品与客户/供应商导出

## 关键决策落档

| 决策 | 记录位置 |
|---|---|
| 移动加权平均计价（粒度仓库×SKU，批次/SN 只追溯） | ADR-0001 |
| 双层库存行 + 双层锁定 + SN 主表 + JSON 明细 | ADR-0002 |
| 作废冲正 = 流水重放终态、逆序作废、不级联 | ADR-0003 |
| 盘点锁 = 盘点单状态派生，无物理锁表 | ADR-0004 |
| 库存并发 FOR UPDATE 顺序加锁（修订 @Version 乐观锁约定） | ADR-0005 |
| 中间表物理删重绑 / 类别沿用商品权限点 / Excel 行号口径 | issues/03 + 账本 Rulings |
| compose 三服务联调推迟 | 账本（M7 部署演练兑现） |

## 待办与风险登记

- **M3 开工清单**：inv_stock/inv_lot_stock/inv_sn/inv_stock_lock/inv_flow 实体与 Mapper、StockService 门面（均价算法纯函数 + 顺序加锁 + 余额快照）、对账器（property 测试收尾断言）、期初单 + Excel（含批次/SN/期初价）、库存查询三级下钻页
- 仓库/客户/供应商**删除引用校验** TODO（M3/M6 经事件补齐，工单 03 已登记）
- 二期启动时：V2 admin 密码替换工具测试（`GenAdminHash`，@Disabled）与发票税率列 V3 迁移
- 全程**未 commit**（维护者规则）：M0–M2 文件量大，建议按里程碑分三个 commit 由人审后落库
