# M7 打印 + 导出 + 联调验收 + 部署

Status: ready-for-agent
Blocked by: 07
Spec: ../spec.md（§6-M7、§7 前端约定）

## 任务

- [ ] 打印视图 `/print/:docType/:id`：11 种单据固定模板（无 Layout、@media print CSS、A4）；成本列按 inventory:cost:view 权限隐藏；打印数据接口（头+行+往来单位+仓库）
- [ ] 各列表/单据 Excel 导出补齐（EasyExcel，收敛在 XxxExcelService）
- [ ] 对照 spec §8 边界口径清单（18 项）端到端手工联调
- [ ] Linux 服务器 Docker Compose 部署演练 + mysqldump cron 备份脚本 + 备份恢复演练
- [ ] 部署文档：注明 Sa-Token 内存模式重启全员重登录（§8-18）、单据跳号正常（ADR-0003）

## 验收

- 11 种单据浏览器打印效果合格（手工验收项）
- 导出服务测试：EasyExcel 写出字节可被读回断言
- 生产演练环境完成一次完整验收业务（期初→采购→销售→退货→盘点→调拨→作废全路径）
- 数据库备份恢复演练通过

## Comments
