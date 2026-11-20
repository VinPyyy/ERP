# M4 审查修复批 — 浏览器手工/自动化验证清单

> 背景：M4 终审指出计划任务 5 声称的"浏览器手工清单留痕"未真实执行（C2 询价页整页不可用即反证）。
> 本清单为修复批补位：Playwright（headless chromium）驱动 Vite dev server（5173）+ 真实后端（8080）+ dev 库，脚本 `C:\Users\Pyw\AppData\Local\Temp\erp-m4-browser-check.py`（验证通过后可复跑；正式 UI 回归套件挂 M7 评估）。

## 结果（2026-11-21，12/12 PASS，console/page JS 错误 0）

- [x] 01 登录（RSA-OAEP 挑战流）→ 布局侧栏可见
- [x] 02 询价列表渲染（page 返回实体、状态列中文标签——C2 契约修复证据）
- [x] 03 新增询价单（商品/供应商下拉、报价录入）→ 成功提示
- [x] 04 审核 → 状态标签"已审核"（此前恒显数字且按钮永久禁用——C2 症状消除）
- [x] 05 比价弹窗标题含单号（此前 `detail.doc.docNo` undefined）
- [x] 06 选中复选框可用（此前 `canEdit` undefined 恒禁用）
- [x] 07 勾选选中成功（PUT select 带 docId 归属校验——M1 修复后仍正常）
- [x] 08 转订单 → 成功提示
- [x] 09 转单后头状态"已转单"
- [x] 10 订单页含新草稿 CGDD（转单数据链完整）
- [x] 11 入库页渲染
- [x] 12 退货页渲染

## 留证截图

- `m4-inquiry-list.png` — 询价列表（状态中文标签、转单后行）
- `m4-return-page.png` — 退货页（批号/SN 列、AUDITED 源单过滤后的页面）

## 未覆盖（如实记录）

- [ ] 入库页生产日期/退货发起按钮的浏览器点击（后端单测 + 冒烟 API 已覆盖逻辑；UI 层留 M5 联调统一回归）
- [ ] 窄屏响应式与低配设备毛玻璃性能（无自动化环境）
- [ ] 盘点锁/溢收拒绝的 UI 触发（dev 库构造成本高，冒烟 API 层覆盖）

## 复跑方式

```bash
# 后端 8080 需已起；python 需 playwright
cd ~/.claude/skills/webapp-testing
python scripts/with_server.py --timeout 90 \
  --server "cd 'D:/Program Files/JetBrains/IdeaProjects/ERP/frontend' && npm run dev" --port 5173 \
  -- python "C:/Users/Pyw/AppData/Local/Temp/erp-m4-browser-check.py"
```
