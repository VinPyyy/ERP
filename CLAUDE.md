# ERP

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles, used as-is as `Status:` strings: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## 文档约定

每次功能性/行为性改动必须同批落地对应详细文档（`docs/` 下按主题建目录）；决策类改动同时补 `docs/adr/`。文档至少覆盖：背景与动机、接口/行为契约、代码地图（文件→职责）、验证记录（测试或冒烟结果）、已知边界与残余风险。纯样式微调不强制，但凡改变行为、接口或部署语义的改动一律适用。
