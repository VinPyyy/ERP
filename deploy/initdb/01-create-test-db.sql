-- 容器首次初始化时额外建测试库（服务层测试 @Transactional 回滚，Flyway 自动迁移）
CREATE DATABASE IF NOT EXISTS erp_test
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
