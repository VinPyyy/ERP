# M0 脚手架与工程基线

Status: ready-for-agent
Spec: ../spec.md（§1 包结构、§2.0 全局约定、§6-M0）

## 任务

- [ ] pom.xml 增补依赖：mybatis-plus-spring-boot3-starter 3.5.x、mysql-connector-j、flyway-core + flyway-mysql、sa-token-spring-boot3-starter、easyexcel 4.x、spring-boot-starter-validation、springdoc-openapi、archunit-junit5(test)
- [ ] common 包全套：R<T>、PageQuery/PageResult、ErrorCode 分段常量、BizException、GlobalExceptionHandler（HTTP 语义：401/403 之外一律 200+业务 code）、BaseEntity（自增 id/create_by/create_time/update_by/update_time/deleted）、MetaObjectHandler、全局枚举（DocStatus 10/15/20/30/40/50、DocType、BizType、MgmtMode、SnStatus）、单据事件基类
- [ ] application.yml 多环境（dev/test/prod）；test 指向本机 MySQL 独立库 erp_test
- [ ] Flyway V1__schema.sql（38 张表 DDL，按 spec §2）+ V2__init_data.sql（admin、五预置角色、权限点种子、默认参数 stock.allow.negative=false / purchase.over.receive.ratio=0、编号规则、admin 仓库绑定 warehouse_id=0）
- [ ] docker-compose.yml：mysql8（卷持久化/healthcheck/TZ/utf8mb4）+ backend + frontend nginx（反代 /api）；Dockerfile ×2、nginx.conf
- [ ] frontend/ 脚手架：Vite + Vue3 + TS + Element Plus + Pinia + vue-router + axios 封装（拦截器：code≠0 toast、401 跳登录）+ dev proxy；DocumentPage 公共组件与 useTable composable 骨架、v-perm 指令
- [ ] ArchUnit 规则测试（spec §1 调用规则 6 条）
- [ ] 测试基建：DataFactory、对账器断言工具骨架（M3 补实现）

## 验收（TDD：先写测试）

- context loads；R 序列化格式测试；GlobalExceptionHandler 各分支 MockMvc 测试
- ArchUnit 全绿；`mvn test` 全绿
- `docker compose up` 三服务健康；Flyway 迁移成功（V1+V2 对空库跑通）

## Comments
