# M1 认证与系统管理

Status: resolved
Blocked by: 01
Spec: ../spec.md（§2.1 system 表、§5 API 约定）

## 任务

- [x] Sa-Token 配置（内存模式，无 Redis——ADR 约束：不得引入 Redis）；BCrypt 密码
- [x] 登录/登出/me：me 返回用户信息 + 权限码集合 + 可见仓库（CurrentUserHolder）
- [x] 用户/角色/权限点 CRUD + 角色授权树；预置五角色（采购员/销售员/仓管/财务/管理员，built_in 不可删）
- [x] 用户仓库绑定（warehouse_id=0 = 全部仓库含未来新增；无绑定 = 看不到任何库存数据）
- [x] 系统参数 ConfigService（进程内 ConcurrentHashMap 缓存，更新刷新，类型转换 BOOL/DECIMAL/INT/STRING）
- [x] 单据编号 DocNoService：单事务行锁（FOR UPDATE）取号 + 期间变更重置 + 作废不回收；格式 {prefix}-{yyyyMMdd}-{4位流水}
- [x] 前端：登录页、主框架 Layout（菜单按权限过滤+顶栏用户/仓库切换）、路由守卫、系统管理 4 页（user/role/config/doc-no）

## 验收（TDD：先写测试）

- AuthService：密码错/停用/权限聚合/仓库范围计算
- DocNoService：50 线程并发取号断言无重号
- ConfigService：类型转换与缓存刷新
- 五角色登录返回正确权限码；仅绑仓库 A 的用户 me 只含 A

## Comments

- 2026-11-20 完成：后端 54/54 绿（Auth 8/Config 4/DocNo 4/User 7/Role 5/Controller 薄测 8），npm build 通过；V2 admin 密码已替换真实 BCrypt（admin123）。裁决见 .superpowers/sdd 账本。
