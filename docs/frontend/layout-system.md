# 布局与设计系统（统一页面骨架）

> 2026-11-20 v2：全站移植 DataPulse demo 视觉语言（用户经预览页两轮确认）——lavender 渐变底 + 白色毛玻璃浮层 + 鲜绿 CTA + 蓝紫点缀。信息架构与页面骨架（PageHeader → 卡片 → 表格 → 分页）不变。v1（enterprise navy 深色侧栏）被本版替代。

## 1. 设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 背景 | lavender 渐变 + 蓝/紫/绿三处 radial 光斑（`.erp-app-bg`） | 玻璃必须"透得出东西"，光斑是玻璃效果的前提 |
| 表面 | 侧栏/顶栏/卡片/弹窗统一玻璃配方：58% 白 + blur(18px) saturate(1.4) + 发丝亮边（`.erp-glass`） | demo 的浮层卡片语言；弹窗用 78% 白 + 22px blur 保表单可读 |
| 主 CTA | **鲜绿实心** `#22C55E`（Element `--el-color-primary` 全局改绿） | demo 的 CTA 色；绿同时是"成功/启用"语义色，用形态拉开：CTA 实心大按钮 vs 状态浅底胶囊+圆点 |
| 交互蓝 | 行内链接、焦点环、分页当前页、选中菜单项 = `#2F7CF6` | "绿=确认动作，蓝=导航/交互"的语义分工，避免整页全绿 |
| 渐变强调 | 页头标题尾词、问候语、logo mark、头像用蓝→紫渐变（`.erp-grad-text`） | demo 的 signature；限标题级元素使用，正文禁用 |
| 圆角 | 控件 10px / 卡片 16px / 浮层 20-24px | 大圆角是 demo 质感的一半 |
| 表格 | 透明底落在玻璃卡上、表头浅染 12px/700、悬停行淡蓝 | 数据层保持绝对清晰，玻璃只到容器级 |

## 2. 代码地图

| 位置 | 职责 |
|---|---|
| `frontend/src/styles/tokens.css` | **唯一颜色来源**：色板/玻璃配方/间距/圆角令牌 + Element 全局覆盖（primary 绿、link 蓝、focus 蓝、dialog 玻璃、tag 胶囊）+ `.erp-page/.erp-toolbar/.erp-table/.erp-pager/.erp-btn-soft/.erp-grad-text/.erp-app-bg/.erp-glass`。页面内禁止裸 hex |
| `frontend/src/layouts/BasicLayout.vue` | 玻璃侧栏（渐变 logo、浅色菜单、active=蓝软底）+ 玻璃顶栏（面包屑末级加粗、渐变头像） |
| `frontend/src/components/PageHeader.vue` | `title` + `accent`（渐变强调词）+ `subtitle`，`#extra` 放主操作 |
| `frontend/src/views/home/index.vue` | 工作台：badge-pill + 渐变问候 + 4 张真实数据 KPI 卡（权限点/角色/可见仓库/里程碑） |
| `frontend/src/views/login/index.vue` | 居中玻璃卡 + 两个浮动信任 chip（≤720px 隐藏）；加密登录逻辑不变 |
| `views/system/*`、`views/basedata/*` | 7 个列表页，统一骨架 + accent 标题 |

## 3. 按钮图标规范（沿用，不随皮肤变）

Element Plus 内置图标，icon+文字，`#icon` 插槽写法（已全局注册，无需 import）：

| 按钮 | 图标 | 按钮 | 图标 |
|---|---|---|---|
| 查询 | `Search` | 编辑 | `Edit` |
| 新增（各类） | `Plus` | 修改（参数类） | `EditPen` |
| 导入 Excel | `Upload` | 删除 | `Delete` |
| 导出 | `Download` | 授权 | `Key` |
| 保存 / 确定 | `Check` | 重置密码 | `Refresh` |
| 取消 / 关闭 | `Close` | 登录 | `Position` |

- 查询按钮加 `.erp-btn-soft`（浅蓝底蓝字）；新增/保存/登录走全局绿主按钮。
- 例外：商品类别树迷你按钮（改/子/删）纯文字。

## 4. 新页面接入规范（M3+ 单据页照此办）

1. 根节点 `<div class="erp-page">`，第一层固定 `<PageHeader title accent subtitle>` + `<el-card shadow="never">`（自动玻璃化）。
2. 主操作进 `#extra`；过滤条用 `.erp-toolbar`。
3. 表格 `class="erp-table"`，分页 `class="erp-pager"`；页面 scoped 不写骨架样式、不写裸 hex。
4. 路由带 `meta: { title }`。
5. 单据页（DocumentPage 系）沿用同一骨架，状态标签放 header 右侧。

## 5. 验证记录

- v2（2026-11-20）：`npm run build`（vue-tsc + vite）通过；`node scripts/smoke/auth-smoke.cjs` **12/12**（UI 改版未触碰接口链路，作为回归证据）；浏览器人工核对登录/工作台/列表/弹窗。
- 附带修复：`stores/auth.ts` MeInfo 补 `roles: string[]`（后端 `/auth/me` 一直返回该字段，store 类型漏了）。

## 6. 已知边界

- `backdrop-filter` 在旧内核/低配设备可能掉帧：`@supports` 回退 92% 实色白（布局不崩）；仓库终端等低配机位如反馈卡顿，考虑加"降玻璃"开关。
- 玻璃上正文对比度依赖文字色不变（#0F172A/#64748B 落在 ≥92% 亮度表面）；后续如调淡玻璃透明度，需重新实测。
- 无暗色模式；侧栏无折叠模式（菜单 >40 项时再评估）。
- 面包屑两级；M7 单据详情接入后需第三级。
