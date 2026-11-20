# ERP

进销存系统：Spring Boot 3（Sa-Token / MyBatis-Plus / Flyway）+ Vue 3（Element Plus / Pinia / Vite）+ MySQL 8。

## 项目事实

- 前端挂 `/erp` 前缀，后端接口 `/api/**` 不带前缀；响应封套 `R{code,msg,data}`，业务码定义在 `ErrorCode`（1xxxx 认证 / 2-5xxxx 业务 / 6xxxx 系统）。
- 分层由 ArchUnit 强制（controller→service→mapper，门面规则见 ADR 0005）；改结构先看 `ArchitectureTest`。
- 前端 base 唯一定义在 `vite.config.ts`，router 经 `import.meta.env.BASE_URL` 跟随，禁止两处硬编码。
- 密码：BCrypt 存储、RSA-OAEP 一次性挑战传输（ADR 0006，细节 docs/security/password-encryption.md）。
- 逻辑删除表的唯一索引必须考虑已删行（教训与规则见 docs/system/username-lifecycle.md）。

## 命令

| 用途 | 命令 |
|---|---|
| 数据库 | `docker compose up -d mysql`（3307，避开本机 5.7） |
| 后端测试 | `mvn test`（test profile 走 erp_test 库，事务回滚） |
| 后端运行 | `mvn spring-boot:run -Dspring-boot.run.profiles=dev`（8080） |
| 前端开发/构建 | `cd frontend && npm run dev` / `npm run build`（含 vue-tsc） |
| 冒烟 | `node scripts/smoke/auth-smoke.cjs`（需后端已起，12 用例，FAIL 退出码非 0） |

## 工作流

- 需求先行：issue/spec 落 `.scratch/<feature-slug>/`，有方案再动手。
- 测试先行：实现前写失败测试；`mvn test` 与 `npm run build` 全绿前不得声称完成。
- 改动同批带文档（见「文档约定」）；冒烟同批带可复跑用例。
- 提交：简洁英文；功能分支开发，fast-forward 进 main；不自动 push。

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles, used as-is as `Status:` strings: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## 文档约定

- 行为/接口/部署改动 → 同批落地文档（`docs/<主题>/`）；决策类另补 `docs/adr/`。纯样式微调豁免。
- 文档最小集：动机、契约、代码地图、验证记录、已知边界。
- 冒烟 → `scripts/smoke/` 可复跑断言用例，并登记 `docs/testing/smoke-test-plan.md` 矩阵；禁止一次性脚本。
