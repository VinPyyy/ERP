# ERP 进销存系统

单租户自用的进销存系统：采购（询价→订单→入库→退货）、销售（报价→订单→出库→退货）、库存（多仓库/批次/SN 追溯/盘点/调拨）、二期财务（应收应付/核销/发票）、三期分析（预警/毛利/看板）。模块化单体，单人开发 + AI 辅助。

## 文档索引

| 文档 | 内容 |
|---|---|
| [GLOSSARY.md](GLOSSARY.md) | **术语权威**——代码命名、单据文案、issue 标题都用这里的词 |
| [docs/adr/](docs/adr/) | 5 篇架构决策记录：计价方法、双层库存行、流水重放冲正、盘点锁、悲观行锁 |
| [.scratch/erp-phase1/spec.md](.scratch/erp-phase1/spec.md) | 一期完整规格：38 组表设计、模型规则、时序、API、边界口径 18 条 |
| [.scratch/erp-phase1/issues/](.scratch/erp-phase1/issues/) | M0–M7 里程碑工单（含完成记录） |
| [.scratch/erp-phase1/status.md](.scratch/erp-phase1/status.md) | 当前进度快照 |
| `docs/agents/` | 工单/术语/ADR 的维护约定 |

## 技术栈

- 后端：**Java 17 + Spring Boot 3.3.5** · MyBatis-Plus 3.5.9 · MySQL 8 · Flyway · Sa-Token（内存模式，无 Redis）· EasyExcel · springdoc
- 前端：**Vue 3 + TypeScript + Element Plus + Vite**（`frontend/`，后端只出 REST API）
- 部署：Docker Compose（mysql:8 + backend jar + frontend nginx），Linux 服务器
- 工程约束：ArchUnit 守护模块依赖方向（`src/test/java/com/wille/erp/ArchitectureTest.java`）

## 开发环境

前置：JDK 17、Maven 3.9+、Node 20+、Docker Desktop。

```bash
# 1. 数据库（映射 3307，避开本机其他 MySQL）
docker compose up -d mysql
# 国内网络直连 Docker Hub 失败时，先经镜像源拉取：
#   docker pull hub.rat.dev/library/mysql:8.0 && docker tag hub.rat.dev/library/mysql:8.0 mysql:8.0
# 容器首启自动建 erp_dev / erp_test 库（deploy/initdb/）

# 2. 后端（dev profile → 127.0.0.1:3307，Flyway 自动迁移）
mvn spring-boot:run

# 3. 前端（Vite dev，/api 代理到 8080）
cd frontend && npm install && npm run dev

# 测试（需 mysql 容器运行）
mvn test
```

- 数据库口令：默认 `erp_local_2026`（`ERP_DB_PASSWORD` 环境变量覆盖）；prod profile 全部走环境变量注入
- npm 镜像：`frontend/.npmrc` 已配置 npmmirror
- **默认账号：admin / admin123 —— 上线后立即改密**

## 当前状态

一期 M0–M2 已完成（脚手架/认证系统管理/基础资料+Excel），M3 库存核心进行中未启动。详见 [status.md](.scratch/erp-phase1/status.md)。

已知注意事项：

- Sa-Token 会话存内存：后端重启后全员重新登录（单实例自用，刻意如此，勿为此引入 Redis）
- 单据作废不回收编号：跳号属正常现象（ADR-0003）
- 中间表（用户角色/角色权限/用户仓库）重绑为物理删除，规避逻辑删除撞唯一键
- 系统参数缓存进程级：直接改库不会刷新缓存，走 `/api/system/configs` 接口修改

## 约定

- 不自动 `git commit`/`push`（见维护者规则）；里程碑边界由人审后提交
- TDD：行为代码先写失败测试；对账器断言是库存测试的收尾关卡
- 术语冲突时以 GLOSSARY.md 为准；决策冲突以 ADR 为准，推翻需新 ADR
