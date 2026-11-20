# 事务边界约定（自调用、缓存一致性与回滚语义）

> 2026-11-20 落地。回答两个反复出现的问题：私有方法在 `@Transactional` 里调用有没有事务问题；哪些"提交前副作用"会在回滚时造成不一致。

## 1. 自调用与代理：什么时候真的有事务问题

Spring 事务靠代理生效，只有**跨 bean 的 public 方法调用**会经过代理。由此：

| 写法 | 事务语义 | 结论 |
|---|---|---|
| public `@Transactional` 方法内部调用**本类 private 方法**（如 `StockServiceImpl.reverse → writeBack/applyLotDelta/applySnReverse`） | 私有方法运行在调用方的事务上下文里（事务绑定线程，与代理无关） | ✅ 安全，这是正确形态 |
| public 无事务方法 `this.` 调用本类另一个 `@Transactional` public 方法 | 绕过代理，**注解静默失效**，无事务 | ❌ 禁止——门面类 javadoc 已立规矩 |
| A 服务注入 B 服务并调用其 `@Transactional` 方法（`OpeningService.audit → StockService.inbound`） | 经代理，REQUIRED 传播加入外层事务 | ✅ 标准形态 |

**审查方式**：新增写路径时确认每个 `@Transactional` public 方法只能被外部 bean 调用进入；同类内需要复用逻辑就抽 private 方法，不要抽 public 事务方法再自调用。

## 2. 提交前副作用清单（回滚不一致的真正来源）

事务回滚会撤销 DB 写，但**撤销不了事务内的非 DB 副作用**。当前代码库的三类副作用与处置：

| 副作用 | 风险 | 处置 | 位置 |
|---|---|---|---|
| 进程缓存刷新（系统参数） | 回滚后缓存残留未提交值 → 缓存-库永久不一致 | **写事务内立即可见 + 登记 `afterCompletion` 回滚失效钩子**（非提交状态逐出键，下次读从 DB 重载真值） | `ConfigServiceImpl.refreshCache` |
| 单据取号（自增序列） | 回滚后号被消耗（跳号） | **有意接受**：spec §8-17"作废不回收号，跳号正常"；审计链完整性优先于号连续 | `DocNoService` |
| Spring 事件发布（二期财务钩子） | 回滚后监听者已消费事件 | 一期只发布无监听者；M4+ 若加监听必须用 `@TransactionalEventListener(phase=AFTER_COMMIT)` | `common/event` |

缓存策略的取舍记录：`afterCommit` 才写缓存（第一版方案）会让**同事务内读不到刚写的值**，破坏"修改后即时生效"语义（property/move 测试当场炸出），故改为"立即写 + 回滚逐出"。残余代价：并发事务在提交前可能读到新缓存值——单实例管理参数低频变更，接受。

## 3. 验证记录（2026-11-20）

- `ConfigServiceTest.rolledBackTransactionDoesNotPolluteCache`：TransactionTemplate 内 update 后 setRollbackOnly，断言 `getBool` 仍为旧值——修复前 FAIL（缓存污染），修复后 PASS。
- 第一版 afterCommit 方案曾致 `StockServiceMoveTest.negativeSwitchMatrix`、`StockServicePropertyTest` 2 例失败（事务内读不到），第二版全量 **166/166** 绿。

## 4. 已知边界

- 回滚逐出与并发读之间存在毫秒级窗口（读到未提交值后回滚）——单实例部署下影响面为"参数生效延迟一次读"，接受。
- 同一事务内对同一 key 多次 update 后回滚：逐出策略天然正确（不依赖恢复旧值快照）。
- M4/M5 引入 Redis 与否都不改变本约定：换分布式缓存后"回滚失效"应改为事务消息或延迟双删。
