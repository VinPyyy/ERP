-- =====================================================================
-- V2 种子数据：admin、五预置角色、权限点、默认参数、11 种单据编号规则
-- 权限点 id 显式指定：100-500 菜单段，1000+ 按钮段（parent_id 引用稳定）
-- =====================================================================

-- ---------- 用户（admin 初始密码 admin123，BCrypt hash；上线后立即改密！重生成：GenAdminHash 工具测试） ----------
INSERT INTO sys_user (id, username, password, real_name, status, create_time, remark) VALUES
(1, 'admin', '$2a$10$NwRzpR6I.2OwXLZU.33IeOLDrZHCBLOc2bsJzJQk9NOzE5q83vxpu', '系统管理员', 1, NOW(), '内置管理员');

-- ---------- 五预置角色（built_in=1 不可删） ----------
INSERT INTO sys_role (id, role_code, role_name, built_in, create_time) VALUES
(1, 'PURCHASER', '采购员', 1, NOW()),
(2, 'SALES',     '销售员', 1, NOW()),
(3, 'KEEPER',    '仓管',   1, NOW()),
(4, 'FINANCE',   '财务',   1, NOW()),
(5, 'ADMIN',     '管理员', 1, NOW());

INSERT INTO sys_user_role (user_id, role_id, create_time) VALUES (1, 5, NOW());
-- admin 绑定全部仓库（0 = 全部，含未来新增）
INSERT INTO sys_user_warehouse (user_id, warehouse_id, create_time) VALUES (1, 0, NOW());

-- ---------- 权限点：根菜单 ----------
INSERT INTO sys_permission (id, perm_code, perm_name, type, parent_id, route_path, icon, sort_no, create_time) VALUES
(100, 'basedata:menu',  '基础资料', 'MENU', 0, '/basedata',  'Files',    10, NOW()),
(200, 'purchase:menu',  '采购管理', 'MENU', 0, '/purchase',  'ShoppingCart', 20, NOW()),
(300, 'sale:menu',      '销售管理', 'MENU', 0, '/sale',      'Sell',     30, NOW()),
(400, 'inventory:menu', '库存管理', 'MENU', 0, '/inventory', 'Box',      40, NOW()),
(500, 'system:menu',    '系统管理', 'MENU', 0, '/system',    'Setting',  50, NOW());

-- ---------- 权限点：页面菜单 ----------
INSERT INTO sys_permission (id, perm_code, perm_name, type, parent_id, route_path, sort_no, create_time) VALUES
(110, 'basedata:product:list',   '商品',     'MENU', 100, '/basedata/product',   11, NOW()),
(120, 'basedata:customer:list',  '客户',     'MENU', 100, '/basedata/customer',  12, NOW()),
(130, 'basedata:supplier:list',  '供应商',   'MENU', 100, '/basedata/supplier',  13, NOW()),
(140, 'basedata:warehouse:list', '仓库',     'MENU', 100, '/basedata/warehouse', 14, NOW()),
(210, 'purchase:inquiry:list',   '询价单',   'MENU', 200, '/purchase/inquiry',   21, NOW()),
(220, 'purchase:order:list',     '采购订单', 'MENU', 200, '/purchase/order',     22, NOW()),
(230, 'purchase:inbound:list',   '采购入库', 'MENU', 200, '/purchase/inbound',   23, NOW()),
(240, 'purchase:return:list',    '采购退货', 'MENU', 200, '/purchase/return',    24, NOW()),
(310, 'sale:quote:list',         '销售报价', 'MENU', 300, '/sale/quote',         31, NOW()),
(320, 'sale:order:list',         '销售订单', 'MENU', 300, '/sale/order',         32, NOW()),
(330, 'sale:outbound:list',      '销售出库', 'MENU', 300, '/sale/outbound',      33, NOW()),
(340, 'sale:return:list',        '销售退货', 'MENU', 300, '/sale/return',        34, NOW()),
(410, 'inventory:stock:list',    '库存查询', 'MENU', 400, '/inventory/stock',    41, NOW()),
(420, 'inventory:flow:list',     '库存流水', 'MENU', 400, '/inventory/flow',     42, NOW()),
(430, 'inventory:opening:list',  '期初库存', 'MENU', 400, '/inventory/opening',  43, NOW()),
(440, 'inventory:transfer:list', '调拨单',   'MENU', 400, '/inventory/transfer', 44, NOW()),
(450, 'inventory:count:list',    '盘点单',   'MENU', 400, '/inventory/count',    45, NOW()),
(510, 'system:user:list',        '用户',     'MENU', 500, '/system/user',        51, NOW()),
(520, 'system:role:list',        '角色',     'MENU', 500, '/system/role',        52, NOW()),
(530, 'system:config:list',      '系统参数', 'MENU', 500, '/system/config',      53, NOW()),
(540, 'system:docno:list',       '编号规则', 'MENU', 500, '/system/doc-no',      54, NOW());

-- ---------- 权限点：按钮（基础资料） ----------
INSERT INTO sys_permission (id, perm_code, perm_name, type, parent_id, sort_no, create_time) VALUES
(1001, 'basedata:product:create',   '商品新增', 'BUTTON', 110, 1, NOW()),
(1002, 'basedata:product:edit',     '商品编辑', 'BUTTON', 110, 2, NOW()),
(1003, 'basedata:product:delete',   '商品删除', 'BUTTON', 110, 3, NOW()),
(1004, 'basedata:product:import',   '商品导入', 'BUTTON', 110, 4, NOW()),
(1005, 'basedata:product:export',   '商品导出', 'BUTTON', 110, 5, NOW()),
(1006, 'basedata:customer:create',  '客户新增', 'BUTTON', 120, 1, NOW()),
(1007, 'basedata:customer:edit',    '客户编辑', 'BUTTON', 120, 2, NOW()),
(1008, 'basedata:customer:delete',  '客户删除', 'BUTTON', 120, 3, NOW()),
(1009, 'basedata:customer:import',  '客户导入', 'BUTTON', 120, 4, NOW()),
(1010, 'basedata:customer:export',  '客户导出', 'BUTTON', 120, 5, NOW()),
(1011, 'basedata:supplier:create',  '供应商新增', 'BUTTON', 130, 1, NOW()),
(1012, 'basedata:supplier:edit',    '供应商编辑', 'BUTTON', 130, 2, NOW()),
(1013, 'basedata:supplier:delete',  '供应商删除', 'BUTTON', 130, 3, NOW()),
(1014, 'basedata:supplier:import',  '供应商导入', 'BUTTON', 130, 4, NOW()),
(1015, 'basedata:supplier:export',  '供应商导出', 'BUTTON', 130, 5, NOW()),
(1016, 'basedata:warehouse:create', '仓库新增', 'BUTTON', 140, 1, NOW()),
(1017, 'basedata:warehouse:edit',   '仓库编辑', 'BUTTON', 140, 2, NOW()),
(1018, 'basedata:warehouse:delete', '仓库删除', 'BUTTON', 140, 3, NOW());

-- ---------- 权限点：按钮（采购） ----------
INSERT INTO sys_permission (id, perm_code, perm_name, type, parent_id, sort_no, create_time) VALUES
(1101, 'purchase:inquiry:create',       '询价新增',   'BUTTON', 210, 1, NOW()),
(1102, 'purchase:inquiry:edit',         '询价编辑',   'BUTTON', 210, 2, NOW()),
(1103, 'purchase:inquiry:audit',        '询价审核',   'BUTTON', 210, 3, NOW()),
(1104, 'purchase:inquiry:void',         '询价作废',   'BUTTON', 210, 4, NOW()),
(1105, 'purchase:inquiry:convert',      '比价转订单', 'BUTTON', 210, 5, NOW()),
(1106, 'purchase:order:create',         '采购订单新增', 'BUTTON', 220, 1, NOW()),
(1107, 'purchase:order:edit',           '采购订单编辑', 'BUTTON', 220, 2, NOW()),
(1108, 'purchase:order:audit',          '采购订单审核', 'BUTTON', 220, 3, NOW()),
(1109, 'purchase:order:void',           '采购订单作废', 'BUTTON', 220, 4, NOW()),
(1110, 'purchase:order:close',          '关闭剩余量',   'BUTTON', 220, 5, NOW()),
(1111, 'purchase:order:create-inbound', '生成入库单',   'BUTTON', 220, 6, NOW()),
(1112, 'purchase:inbound:create',       '入库单新增', 'BUTTON', 230, 1, NOW()),
(1113, 'purchase:inbound:edit',         '入库单编辑', 'BUTTON', 230, 2, NOW()),
(1114, 'purchase:inbound:audit',        '入库单审核', 'BUTTON', 230, 3, NOW()),
(1115, 'purchase:inbound:void',         '入库单作废', 'BUTTON', 230, 4, NOW()),
(1116, 'purchase:inbound:create-return','生成退货单', 'BUTTON', 230, 5, NOW()),
(1117, 'purchase:return:create',        '退货单新增', 'BUTTON', 240, 1, NOW()),
(1118, 'purchase:return:edit',          '退货单编辑', 'BUTTON', 240, 2, NOW()),
(1119, 'purchase:return:audit',         '退货单审核', 'BUTTON', 240, 3, NOW()),
(1120, 'purchase:return:void',          '退货单作废', 'BUTTON', 240, 4, NOW());

-- ---------- 权限点：按钮（销售） ----------
INSERT INTO sys_permission (id, perm_code, perm_name, type, parent_id, sort_no, create_time) VALUES
(1201, 'sale:quote:create',        '报价新增',   'BUTTON', 310, 1, NOW()),
(1202, 'sale:quote:edit',          '报价编辑',   'BUTTON', 310, 2, NOW()),
(1203, 'sale:quote:audit',         '报价审核',   'BUTTON', 310, 3, NOW()),
(1204, 'sale:quote:void',          '报价作废',   'BUTTON', 310, 4, NOW()),
(1205, 'sale:quote:convert',       '转销售订单', 'BUTTON', 310, 5, NOW()),
(1206, 'sale:order:create',        '销售订单新增', 'BUTTON', 320, 1, NOW()),
(1207, 'sale:order:edit',          '销售订单编辑', 'BUTTON', 320, 2, NOW()),
(1208, 'sale:order:audit',         '销售订单审核', 'BUTTON', 320, 3, NOW()),
(1209, 'sale:order:void',          '销售订单作废', 'BUTTON', 320, 4, NOW()),
(1210, 'sale:order:close',         '关闭剩余量',   'BUTTON', 320, 5, NOW()),
(1211, 'sale:order:create-outbound','生成出库单',  'BUTTON', 320, 6, NOW()),
(1212, 'sale:outbound:create',     '出库单新增', 'BUTTON', 330, 1, NOW()),
(1213, 'sale:outbound:edit',       '出库单编辑', 'BUTTON', 330, 2, NOW()),
(1214, 'sale:outbound:audit',      '出库单审核', 'BUTTON', 330, 3, NOW()),
(1215, 'sale:outbound:void',       '出库单作废', 'BUTTON', 330, 4, NOW()),
(1216, 'sale:outbound:create-return','生成退货单','BUTTON', 330, 5, NOW()),
(1217, 'sale:return:create',       '退货单新增', 'BUTTON', 340, 1, NOW()),
(1218, 'sale:return:edit',         '退货单编辑', 'BUTTON', 340, 2, NOW()),
(1219, 'sale:return:audit',        '退货单审核', 'BUTTON', 340, 3, NOW()),
(1220, 'sale:return:void',         '退货单作废', 'BUTTON', 340, 4, NOW());

-- ---------- 权限点：按钮（库存，含成本可见性独立权限点） ----------
INSERT INTO sys_permission (id, perm_code, perm_name, type, parent_id, sort_no, create_time) VALUES
(1301, 'inventory:cost:view',      '成本可见',   'BUTTON', 410, 1, NOW()),
(1302, 'inventory:sn:adjust',      'SN人工调整', 'BUTTON', 410, 2, NOW()),
(1303, 'inventory:stock:export',   '库存导出',   'BUTTON', 410, 3, NOW()),
(1304, 'inventory:flow:replay',    '流水对账',   'BUTTON', 420, 1, NOW()),
(1305, 'inventory:flow:export',    '流水导出',   'BUTTON', 420, 2, NOW()),
(1306, 'inventory:opening:create', '期初新增',   'BUTTON', 430, 1, NOW()),
(1307, 'inventory:opening:import', '期初Excel导入', 'BUTTON', 430, 2, NOW()),
(1308, 'inventory:opening:audit',  '期初审核',   'BUTTON', 430, 3, NOW()),
(1309, 'inventory:opening:void',   '期初作废',   'BUTTON', 430, 4, NOW()),
(1310, 'inventory:transfer:create','调拨新增',   'BUTTON', 440, 1, NOW()),
(1311, 'inventory:transfer:edit',  '调拨编辑',   'BUTTON', 440, 2, NOW()),
(1312, 'inventory:transfer:audit', '调拨审核',   'BUTTON', 440, 3, NOW()),
(1313, 'inventory:transfer:void',  '调拨作废',   'BUTTON', 440, 4, NOW()),
(1314, 'inventory:count:create',   '盘点新增',   'BUTTON', 450, 1, NOW()),
(1315, 'inventory:count:start',    '开始盘点',   'BUTTON', 450, 2, NOW()),
(1316, 'inventory:count:edit',     '实盘录入',   'BUTTON', 450, 3, NOW()),
(1317, 'inventory:count:audit',    '盘点审核',   'BUTTON', 450, 4, NOW()),
(1318, 'inventory:count:void',     '盘点作废',   'BUTTON', 450, 5, NOW());

-- ---------- 权限点：按钮（系统） ----------
INSERT INTO sys_permission (id, perm_code, perm_name, type, parent_id, sort_no, create_time) VALUES
(1401, 'system:user:create',         '用户新增',   'BUTTON', 510, 1, NOW()),
(1402, 'system:user:edit',           '用户编辑',   'BUTTON', 510, 2, NOW()),
(1403, 'system:user:delete',         '用户删除',   'BUTTON', 510, 3, NOW()),
(1404, 'system:user:reset-password', '重置密码',   'BUTTON', 510, 4, NOW()),
(1405, 'system:role:create',         '角色新增',   'BUTTON', 520, 1, NOW()),
(1406, 'system:role:edit',           '角色编辑',   'BUTTON', 520, 2, NOW()),
(1407, 'system:role:delete',         '角色删除',   'BUTTON', 520, 2, NOW()),
(1408, 'system:role:grant',          '角色授权',   'BUTTON', 520, 3, NOW()),
(1409, 'system:config:edit',         '参数修改',   'BUTTON', 530, 1, NOW()),
(1410, 'system:docno:edit',          '编号规则修改', 'BUTTON', 540, 1, NOW());

-- ---------- ADMIN 角色授全部权限（其余四角色由管理员上线后在界面配置） ----------
INSERT INTO sys_role_permission (role_id, permission_id, create_time)
SELECT 5, id, NOW() FROM sys_permission;

-- ---------- 系统参数默认值 ----------
INSERT INTO sys_config (param_key, param_name, param_value, value_type, create_time, remark) VALUES
('stock.allow.negative',        '允许负库存出库', 'false', 'BOOL',    NOW(), '默认禁止；打开后库存不足仍可出库记负数（spec §3e/Q13）'),
('purchase.over.receive.ratio', '采购溢收比例',   '0',     'DECIMAL', NOW(), '0=禁止溢收；0.1=允许超订购量10%（仅采购，销售超发严格禁止）');

-- ---------- 单据编号规则（前缀与 DocType.prefix 一致；格式 {prefix}-{yyyyMMdd}-{4位流水}） ----------
INSERT INTO sys_doc_no_rule (doc_type, prefix, date_pattern, seq_length, seq_scope, current_seq, create_time) VALUES
('PUR_INQUIRY',  'CGXJ', 'yyyyMMdd', 4, 'DAY', 0, NOW()),
('PUR_ORDER',    'CGDD', 'yyyyMMdd', 4, 'DAY', 0, NOW()),
('PUR_INBOUND',  'CGRK', 'yyyyMMdd', 4, 'DAY', 0, NOW()),
('PUR_RETURN',   'CGTH', 'yyyyMMdd', 4, 'DAY', 0, NOW()),
('SAL_QUOTE',    'XSBJ', 'yyyyMMdd', 4, 'DAY', 0, NOW()),
('SAL_ORDER',    'XSDD', 'yyyyMMdd', 4, 'DAY', 0, NOW()),
('SAL_OUTBOUND', 'XSCK', 'yyyyMMdd', 4, 'DAY', 0, NOW()),
('SAL_RETURN',   'XSTH', 'yyyyMMdd', 4, 'DAY', 0, NOW()),
('TRANSFER',     'DB',   'yyyyMMdd', 4, 'DAY', 0, NOW()),
('COUNT',        'PD',   'yyyyMMdd', 4, 'DAY', 0, NOW()),
('OPENING',      'QC',   'yyyyMMdd', 4, 'DAY', 0, NOW());
