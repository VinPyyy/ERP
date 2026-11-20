-- =====================================================================
-- ERP 一期 Schema（spec §2，41 张表）
-- 方言：MySQL 8（兼容 5.7：utf8mb4_unicode_ci，不用 8-only 特性）
-- 约定：BIGINT 自增主键；公共字段 create_by/create_time/update_by/update_time/deleted/remark；
--       数量 DECIMAL(18,4)、单价 DECIMAL(18,6)、金额 DECIMAL(18,2)；
--       单据头统一 doc_no/status/doc_date/currency/audit_*/void_*/fin_flag；
--       多态引用列存枚举名 VARCHAR(30)（spec §2.0）；
--       唯一键为硬约束（逻辑删除后同码重建属运维场景，不在 DB 层放宽）。
-- =====================================================================

-- ==================== system（8） ====================

CREATE TABLE sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL COMMENT '登录名',
  password VARCHAR(100) NOT NULL COMMENT 'BCrypt',
  real_name VARCHAR(50) NULL,
  phone VARCHAR(20) NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  last_login_time DATETIME NULL,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';

CREATE TABLE sys_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_code VARCHAR(50) NOT NULL,
  role_name VARCHAR(50) NOT NULL,
  built_in TINYINT NOT NULL DEFAULT 0 COMMENT '预置角色不可删',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_sys_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';

CREATE TABLE sys_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  perm_code VARCHAR(100) NOT NULL COMMENT '如 purchase:order:audit / inventory:cost:view',
  perm_name VARCHAR(50) NOT NULL,
  type VARCHAR(10) NOT NULL COMMENT 'MENU/BUTTON',
  parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '树结构，0=根',
  route_path VARCHAR(200) NULL COMMENT 'MENU 用：前端路由',
  icon VARCHAR(50) NULL,
  sort_no INT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_sys_perm_code (perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限点（菜单+按钮合一）';

CREATE TABLE sys_user_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-角色';

CREATE TABLE sys_role_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_role_perm (role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-权限';

CREATE TABLE sys_user_warehouse (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL COMMENT '0=全部仓库（含未来新增）',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_user_warehouse (user_id, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户可见仓库（数据权限）';

CREATE TABLE sys_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  param_key VARCHAR(100) NOT NULL,
  param_name VARCHAR(100) NOT NULL,
  param_value VARCHAR(200) NULL,
  value_type VARCHAR(10) NOT NULL COMMENT 'BOOL/DECIMAL/INT/STRING',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_sys_config_key (param_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统参数';

CREATE TABLE sys_doc_no_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_type VARCHAR(30) NOT NULL COMMENT 'DocType 枚举名',
  prefix VARCHAR(10) NOT NULL,
  date_pattern VARCHAR(20) NULL COMMENT 'yyyyMMdd/yyyyMM/空',
  seq_length INT NOT NULL DEFAULT 4,
  seq_scope VARCHAR(10) NOT NULL DEFAULT 'DAY' COMMENT 'DAY/MONTH/NONE',
  current_seq BIGINT NOT NULL DEFAULT 0,
  current_period VARCHAR(20) NULL,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_doc_no_rule_type (doc_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单据编号规则（取号行锁 FOR UPDATE）';

-- ==================== basedata（5） ====================

CREATE TABLE base_category (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL,
  parent_id BIGINT NOT NULL DEFAULT 0,
  sort_no INT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品类别（树）';

CREATE TABLE base_product (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku_code VARCHAR(50) NOT NULL,
  name VARCHAR(100) NOT NULL,
  spec VARCHAR(100) NULL COMMENT '规格',
  unit VARCHAR(20) NOT NULL COMMENT '单一计量单位（不做多单位换算）',
  barcode VARCHAR(50) NULL,
  category_id BIGINT NULL,
  mgmt_mode TINYINT NOT NULL DEFAULT 1 COMMENT '1不管 2管批次 3管SN 4批次+SN；被引用后禁改（应用层）',
  shelf_life_days INT NULL COMMENT '保质期天数（管批次必填）',
  expire_warn_days INT NULL COMMENT '临期预警提前天数（一期预埋只存不算，三期用）',
  ref_sale_price DECIMAL(18,6) NULL COMMENT '参考售价',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_product_sku_code (sku_code),
  KEY idx_product_name (name),
  KEY idx_product_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品（一行=一个SKU）';

CREATE TABLE base_customer (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(50) NOT NULL,
  name VARCHAR(100) NOT NULL,
  contact VARCHAR(50) NULL,
  phone VARCHAR(20) NULL,
  address VARCHAR(200) NULL,
  tax_no VARCHAR(50) NULL COMMENT '税号（二期发票登记预埋）',
  status TINYINT NOT NULL DEFAULT 1,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_customer_code (code),
  KEY idx_customer_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户';

CREATE TABLE base_supplier (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(50) NOT NULL,
  name VARCHAR(100) NOT NULL,
  contact VARCHAR(50) NULL,
  phone VARCHAR(20) NULL,
  address VARCHAR(200) NULL,
  tax_no VARCHAR(50) NULL COMMENT '税号（二期预埋）',
  status TINYINT NOT NULL DEFAULT 1,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_supplier_code (code),
  KEY idx_supplier_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商';

CREATE TABLE base_warehouse (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(50) NOT NULL,
  name VARCHAR(100) NOT NULL,
  address VARCHAR(200) NULL,
  keeper VARCHAR(50) NULL COMMENT '负责人',
  status TINYINT NOT NULL DEFAULT 1,
  sort_no INT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_warehouse_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='仓库（≤10，数据权限单位）';

-- ==================== inventory（12） ====================

CREATE TABLE inv_lot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku_id BIGINT NOT NULL,
  lot_no VARCHAR(50) NOT NULL,
  production_date DATE NULL,
  expiry_date DATE NULL COMMENT '=生产日期+保质期（冗余可手改，三期临期预警直接查此列）',
  supplier_id BIGINT NULL COMMENT '来源批次，可空；同SKU同批号跨供应商复用',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_lot_sku_no (sku_id, lot_no),
  KEY idx_lot_expiry (expiry_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品批次主档（全局，不按仓库）';

CREATE TABLE inv_stock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  warehouse_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '实际库存',
  locked_qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '订单锁定汇总；可用量=qty-locked_qty（派生不落库）',
  cost_price DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '移动加权平均均价（仓库×SKU 粒度，ADR-0001）',
  cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本金额（金额权威、单价派生，ADR-0002）',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_stock_wh_sku (warehouse_id, sku_id),
  KEY idx_stock_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存主行（计价+可用量权威；并发走 FOR UPDATE，ADR-0005）';

CREATE TABLE inv_lot_stock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  warehouse_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  lot_id BIGINT NOT NULL,
  qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '只记数量不记成本；SUM(批次行)=主行.qty',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_lot_stock (warehouse_id, sku_id, lot_id),
  KEY idx_lot_stock_lot (lot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批次库存行（纯数量分账）';

CREATE TABLE inv_sn (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku_id BIGINT NOT NULL,
  sn_code VARCHAR(100) NOT NULL,
  lot_id BIGINT NULL COMMENT '批次+SN 模式必填；纯 SN 为空',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1在库 2已出库（两态翻转，spec §3b）',
  warehouse_id BIGINT NULL COMMENT '在库有值，出库置 NULL',
  in_doc_type VARCHAR(30) NULL, in_doc_id BIGINT NULL, in_doc_no VARCHAR(32) NULL, in_time DATETIME NULL COMMENT '最近一次入库',
  out_doc_type VARCHAR(30) NULL, out_doc_id BIGINT NULL, out_doc_no VARCHAR(32) NULL, out_time DATETIME NULL COMMENT '最近一次出库',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_sn_sku_code (sku_id, sn_code),
  KEY idx_sn_status_wh (status, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SN 主档（一行一物）';

CREATE TABLE inv_stock_lock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  lock_doc_type VARCHAR(30) NOT NULL DEFAULT 'SAL_ORDER' COMMENT '一期恒 SAL_ORDER',
  lock_doc_id BIGINT NOT NULL,
  lock_item_id BIGINT NOT NULL COMMENT '订单行 id',
  warehouse_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  lock_qty DECIMAL(18,4) NOT NULL COMMENT '原始锁定量',
  remain_qty DECIMAL(18,4) NOT NULL COMMENT '剩余未核销；=0 保留作历史；不变量 locked_qty=SUM(remain)',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_lock_doc (lock_doc_type, lock_doc_id),
  KEY idx_lock_wh_sku (warehouse_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单锁定明细（双层锁定的明细层）';

CREATE TABLE inv_flow (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  warehouse_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  lot_id BIGINT NULL,
  qty DECIMAL(18,4) NOT NULL COMMENT '带符号：入+/出−',
  price DECIMAL(18,6) NULL COMMENT '业务价（采购价/售价），二期财务用；与成本两线分离',
  cost_price DECIMAL(18,6) NOT NULL COMMENT '本次结转/入账成本单价快照',
  cost_amount DECIMAL(18,2) NOT NULL COMMENT '带符号成本金额；作废冲正取此快照（ADR-0003）',
  balance_qty DECIMAL(18,4) NOT NULL COMMENT '变动后主行 qty 快照',
  balance_cost_amount DECIMAL(18,2) NOT NULL,
  balance_cost_price DECIMAL(18,6) NOT NULL,
  biz_type VARCHAR(30) NOT NULL COMMENT 'BizType 枚举名',
  biz_doc_type VARCHAR(30) NOT NULL, biz_doc_id BIGINT NOT NULL, biz_doc_no VARCHAR(32) NOT NULL,
  biz_doc_item_id BIGINT NULL,
  reverse_flow_id BIGINT NULL COMMENT '冲正流水指向原流水，防重复冲正',
  create_by BIGINT NULL COMMENT '=审核人', create_time DATETIME NULL COMMENT '=审核时刻',
  update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'insert-only，恒 0', remark VARCHAR(255) NULL,
  KEY idx_flow_wh_sku_time (warehouse_id, sku_id, create_time),
  KEY idx_flow_doc (biz_doc_type, biz_doc_id),
  KEY idx_flow_lot (lot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存流水（audit trail，永不改删）';

CREATE TABLE inv_opening (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  warehouse_id BIGINT NOT NULL COMMENT '一仓一期初单',
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_opening_doc_no (doc_no),
  KEY idx_opening_wh (warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='期初库存单头（Excel 导入落地载体）';

CREATE TABLE inv_opening_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  lot_id BIGINT NULL, lot_no VARCHAR(50) NULL, production_date DATE NULL COMMENT '管批次必填',
  qty DECIMAL(18,4) NOT NULL,
  cost_price DECIMAL(18,6) NOT NULL COMMENT '期初均价（Excel 给定，允许 0）',
  cost_amount DECIMAL(18,2) NULL,
  sn_list TEXT NULL COMMENT 'JSON 数组（管 SN 必填）',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_opening_item_doc (doc_id),
  KEY idx_opening_item_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='期初库存单行';

CREATE TABLE inv_transfer (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  out_warehouse_id BIGINT NOT NULL,
  in_warehouse_id BIGINT NOT NULL COMMENT '应用层校验 != out',
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_transfer_doc_no (doc_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调拨单头（一步直接调拨）';

CREATE TABLE inv_transfer_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  lot_id BIGINT NULL COMMENT '管批次必填；批次号不变随单平移',
  qty DECIMAL(18,4) NOT NULL,
  sn_list TEXT NULL COMMENT 'JSON（管 SN 必填）',
  cost_amount DECIMAL(18,2) NULL COMMENT '=qty×调出仓均价，审核后回填',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_transfer_item_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调拨单行';

CREATE TABLE inv_count (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10 COMMENT '10草稿/15盘点中(锁生效)/30已完成/50已作废',
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  warehouse_id BIGINT NOT NULL,
  scope_desc VARCHAR(200) NULL COMMENT '盘点范围描述',
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_count_doc_no (doc_no),
  KEY idx_count_wh_status (warehouse_id, status) COMMENT '盘点锁 EXISTS 校验用（ADR-0004）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='盘点单头（状态即锁）';

CREATE TABLE inv_count_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  lot_id BIGINT NULL COMMENT '管批次按批次行盘点；非批次为空',
  book_qty DECIMAL(18,4) NULL COMMENT '开始盘点时快照的账面数（取实际 qty，不减锁定），此后不变',
  count_qty DECIMAL(18,4) NULL COMMENT '实盘数，COUNTING 期间可反复录入',
  diff_qty DECIMAL(18,4) NULL COMMENT '=count-book，审核时定格',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_count_item_doc (doc_id),
  KEY idx_count_item_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='盘点单行（数量语义由 book/count/diff 承担，无通用 qty/price）';

-- ==================== purchase（8） ====================

CREATE TABLE pur_inquiry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL COMMENT '即询价日期',
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  title VARCHAR(100) NULL,
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_inquiry_doc_no (doc_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='询价单头（不碰库存财务）';

CREATE TABLE pur_inquiry_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  supplier_id BIGINT NOT NULL COMMENT '每行=一个 SKU×供应商报价',
  qty DECIMAL(18,4) NOT NULL COMMENT '询价量',
  price DECIMAL(18,6) NULL COMMENT '报价',
  amount DECIMAL(18,2) NULL,
  lead_time_days INT NULL COMMENT '交期（天）',
  selected TINYINT NOT NULL DEFAULT 0 COMMENT '比价选中→转采购订单',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_inquiry_item_doc (doc_id),
  KEY idx_inquiry_item_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='询价单行';

CREATE TABLE pur_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  supplier_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL COMMENT '默认入库仓；带 source 的入库单必须同仓（spec §8-12）',
  expect_date DATE NULL,
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_pur_order_doc_no (doc_no),
  KEY idx_pur_order_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购订单头';

CREATE TABLE pur_order_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  qty DECIMAL(18,4) NOT NULL,
  price DECIMAL(18,6) NULL,
  amount DECIMAL(18,2) NULL,
  done_qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '累计已入库；溢收校验 done≤qty×(1+ratio)',
  line_status TINYINT NOT NULL DEFAULT 10 COMMENT '10执行中/30已完成/40已关闭',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_pur_order_item_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购订单行';

CREATE TABLE pur_inbound (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  supplier_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  source_order_id BIGINT NULL COMMENT 'NULL=无订单直接入库',
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0 COMMENT '二期：审核→应付',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_pur_inbound_doc_no (doc_no),
  KEY idx_pur_inbound_source (source_order_id),
  KEY idx_pur_inbound_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购入库单头（审核动库存、重算均价）';

CREATE TABLE pur_inbound_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  source_order_item_id BIGINT NULL,
  qty DECIMAL(18,4) NOT NULL COMMENT '本次实收',
  price DECIMAL(18,6) NULL COMMENT '实际结算价：默认带订单价可改，决定均价与二期应付',
  amount DECIMAL(18,2) NULL,
  lot_id BIGINT NULL, lot_no VARCHAR(50) NULL, production_date DATE NULL COMMENT '管批次必填，审核时物化 inv_lot',
  sn_list TEXT NULL COMMENT 'JSON（管 SN 必填，数量=qty）',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_pur_inbound_item_doc (doc_id),
  KEY idx_pur_inbound_item_source (source_order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购入库单行';

CREATE TABLE pur_return (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  supplier_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  source_inbound_id BIGINT NULL COMMENT 'NULL=无原单退货（价手录）',
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_pur_return_doc_no (doc_no),
  KEY idx_pur_return_source (source_inbound_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购退货单头（出库方向）';

CREATE TABLE pur_return_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  source_inbound_item_id BIGINT NULL,
  qty DECIMAL(18,4) NOT NULL COMMENT '有原单时累计退货≤原入库量',
  price DECIMAL(18,6) NULL COMMENT '有原单=原单价不可改；无原单手录',
  amount DECIMAL(18,2) NULL,
  lot_id BIGINT NULL, lot_no VARCHAR(50) NULL,
  sn_list TEXT NULL,
  reason VARCHAR(200) NOT NULL,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_pur_return_item_doc (doc_id),
  KEY idx_pur_return_item_source (source_inbound_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购退货单行（成本一律按当前均价结转）';

-- ==================== sale（8） ====================

CREATE TABLE sal_quote (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  customer_id BIGINT NOT NULL,
  valid_until DATE NULL COMMENT '报价有效期；报价不锁库存（spec §8-1）',
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_sal_quote_doc_no (doc_no),
  KEY idx_sal_quote_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售报价单头';

CREATE TABLE sal_quote_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  qty DECIMAL(18,4) NOT NULL,
  price DECIMAL(18,6) NULL,
  amount DECIMAL(18,2) NULL,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_sal_quote_item_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售报价单行';

CREATE TABLE sal_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  customer_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL COMMENT '审核即按仓库×SKU 锁定库存（spec §3e）',
  expect_date DATE NULL,
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_sal_order_doc_no (doc_no),
  KEY idx_sal_order_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售订单头';

CREATE TABLE sal_order_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  qty DECIMAL(18,4) NOT NULL,
  price DECIMAL(18,6) NULL,
  amount DECIMAL(18,2) NULL,
  done_qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '累计已出库；超发严格禁止（spec §8-5）',
  line_status TINYINT NOT NULL DEFAULT 10 COMMENT '10执行中/30已完成/40已关闭',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_sal_order_item_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售订单行';

CREATE TABLE sal_outbound (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  customer_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  source_order_id BIGINT NULL COMMENT 'NULL=直接出库（不动锁定）',
  carrier VARCHAR(50) NULL COMMENT '承运商，空=自提；出库即发货（spec §2.5）',
  tracking_no VARCHAR(50) NULL COMMENT '运单号',
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0 COMMENT '二期：审核→应收',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_sal_outbound_doc_no (doc_no),
  KEY idx_sal_outbound_source (source_order_id),
  KEY idx_sal_outbound_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售出库单头（出库即发货，含物流字段）';

CREATE TABLE sal_outbound_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  source_order_item_id BIGINT NULL,
  qty DECIMAL(18,4) NOT NULL COMMENT '有 source 时 ≤ 订单行剩余量（硬校验）',
  price DECIMAL(18,6) NULL COMMENT '默认订单价可改',
  amount DECIMAL(18,2) NULL,
  lot_id BIGINT NULL, lot_no VARCHAR(50) NULL,
  sn_list TEXT NULL COMMENT 'JSON（管 SN 必填）',
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_sal_outbound_item_doc (doc_id),
  KEY idx_sal_outbound_item_source (source_order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售出库单行';

CREATE TABLE sal_return (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_no VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 10,
  doc_date DATE NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  customer_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL COMMENT '退货入库仓',
  source_outbound_id BIGINT NULL COMMENT 'NULL=无原单退货（价手录，用户裁决 spec §8-6）',
  audit_by BIGINT NULL, audit_time DATETIME NULL,
  void_by BIGINT NULL, void_time DATETIME NULL, void_reason VARCHAR(200) NULL,
  fin_flag TINYINT NOT NULL DEFAULT 0,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  UNIQUE KEY uk_sal_return_doc_no (doc_no),
  KEY idx_sal_return_source (source_outbound_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售退货单头（入库方向）';

CREATE TABLE sal_return_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_name VARCHAR(100) NOT NULL, spec VARCHAR(100) NULL, unit VARCHAR(20) NULL,
  source_outbound_item_id BIGINT NULL,
  qty DECIMAL(18,4) NOT NULL COMMENT '有原单时累计退货≤原出库量',
  price DECIMAL(18,6) NULL COMMENT '有原单=原单价不可改；无原单手录',
  amount DECIMAL(18,2) NULL,
  lot_id BIGINT NULL, lot_no VARCHAR(50) NULL,
  sn_list TEXT NULL COMMENT 'JSON：从原出库行勾选 status=OUT 且未被退过的 SN（spec §8-3）',
  reason VARCHAR(200) NOT NULL,
  create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(255) NULL,
  KEY idx_sal_return_item_doc (doc_id),
  KEY idx_sal_return_item_source (source_outbound_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售退货单行（成本按当前均价入账）';
