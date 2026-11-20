# 布局与设计系统（统一页面骨架）

> 2026-11-20 v2.1：全站统一**浅蓝色调**（用户以 ui-ux-pro-max 官网 hero 为参考图确认）：主按钮=亮蓝 #2563EB，背景=浅蓝渐变 + 右下角一抹暖色，渐变强调收进蓝系；绿色仅保留"启用/成功"语义。v2（DataPulse 绿 CTA + lavender）与 v1（enterprise navy）均被替代。信息架构与页面骨架（PageHeader → 卡片 → 表格 → 分页）不变。

## 1. 设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 主 CTA | **亮蓝实心** `#2563EB`（Element `--el-color-primary`），白字对比 4.5:1 达 AA | 对齐参考图 "How it Works" 按钮 |
| 背景 | 浅蓝渐变 `#F2F6FD→#E9EFFB` + 天蓝/蓝光斑 + 右下角淡暖橙一抹（`.erp-app-bg`） | 对齐参考图 hero；玻璃必须"透得出东西"，光斑是玻璃效果的前提 |
| 语义色分工 | 绿=成功/启用（tag、message），红=危险，琥珀=提醒；绿不再出现在按钮/渐变/图标底 | 用户明确要求统一浅蓝；状态用浅底胶囊+圆点与控件拉开 |
| 表面 | 玻璃配方：58% 白 + blur(18px) saturate(1.4) + 发丝亮边（`.erp-glass`）；弹窗 78% 白 + 22px blur | 玻璃只用在"面"，控件保持实心可读 |
| 渐变强调 | 页头 accent 词、问候语、logo mark、头像 = 蓝→天蓝渐变（`.erp-grad-text`）；KPI 图标底用蓝/天蓝/靛蓝三档软底 | 参考图大标题的渐变语言，收进蓝系避免撞色 |
| 圆角 | 控件 10px / 卡片 16px / 浮层 20-24px | 大圆角是 demo 质感的一半 |
| 表格 | 透明底落在玻璃卡上、表头浅染 12px/700、悬停行淡蓝 | 数据层保持绝对清晰，玻璃只到容器级 |

## 2. 代码地图

| 位置 | 职责 |
|---|---|
| `frontend/src/styles/tokens.css` | **唯一颜色来源**：色板/玻璃配方/间距/圆角令牌 + Element 全局覆盖（primary 绿、link 蓝、focus 蓝、dialog 玻璃、tag 胶囊）+ `.erp-page/.erp-toolbar/.erp-table/.erp-pager/.erp-btn-soft/.erp-grad-text/.erp-app-bg/.erp-glass`。页面内禁止裸 hex |
| `frontend/src/layouts/BasicLayout.vue` | 玻璃侧栏（渐变 logo、浅色菜单、active=蓝软底）+ 玻璃顶栏（面包屑末级加粗、渐变头像） |
| `frontend/src/components/PageHeader.vue` | `title` + `accent`（渐变强调词）+ `subtitle`，`#extra` 放主操作 |
| `frontend/src/views/home/index.vue` | 工作台：badge-pill + 渐变问候 + 4 张真实数据 KPI 卡（权限点/角色/可见仓库/里程碑） |
| `frontend/src/views/login/index.vue` | 双栏 hero：左侧品牌叙事（badge-pill + 渐变大标题 + 两张装饰玻璃 mini 卡）+ 右侧玻璃登录卡与浮动信任 chip；≤999px 隐藏叙事区；加密登录逻辑不变 |
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

- 工具栏动作按钮（查询/导入/导出）与新增/保存/登录统一为实心主按钮（全局蓝 primary）；次级形态仅保留"取消"等默认按钮。
- 例外：商品类别树迷你按钮（改/子/删）纯文字。

## 4. 新页面接入规范（M3+ 单据页照此办）

1. 根节点 `<div class="erp-page">`，第一层固定 `<PageHeader title accent subtitle>` + `<el-card shadow="never">`（自动玻璃化）。
2. 主操作（新增等）进 `.erp-toolbar`、紧跟查询按钮右侧（全站统一位置）；`PageHeader` 的 `#extra` 仅留给页级非数据操作。
3. 表格 `class="erp-table"`，分页 `class="erp-pager"`；页面 scoped 不写骨架样式、不写裸 hex。
4. 路由带 `meta: { title }`。
5. 单据页（DocumentPage 系）沿用同一骨架，状态标签放 header 右侧。

## 5. 验证记录

- v2.1（2026-11-20）：`npm run build` 通过；冒烟 `auth-smoke.cjs` **12/12**；浏览器人工核对登录双栏 hero / 工作台 / 列表页 / 弹窗（浅蓝统一后绿仅存在于状态标签与成功提示）。
- v2（2026-11-20）：`npm run build` 通过；冒烟 12/12；浏览器人工核对。
- 附带修复：`stores/auth.ts` MeInfo 补 `roles: string[]`（后端 `/auth/me` 一直返回该字段，store 类型漏了）。

## 6. 已知边界

- `backdrop-filter` 在旧内核/低配设备可能掉帧：`@supports` 回退 92% 实色白（布局不崩）；仓库终端等低配机位如反馈卡顿，考虑加"降玻璃"开关。
- 玻璃上正文对比度依赖文字色不变（#0F172A/#64748B 落在 ≥92% 亮度表面）；后续如调淡玻璃透明度，需重新实测。
- 无暗色模式；侧栏无折叠模式（菜单 >40 项时再评估）。
- 面包屑两级；M7 单据详情接入后需第三级。
