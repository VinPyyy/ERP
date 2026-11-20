# 库存并发控制用悲观行锁（FOR UPDATE 固定顺序），不用 @Version 乐观锁

修订早期默认约定"库存行乐观锁 @Version"：库存扣减是事务内多行联动（主行+批次行+SN+锁定+流水）的写路径，乐观锁冲突后需要整事务重试循环，正确性论证复杂且 AI 辅助开发下极易写错。改为 `SELECT ... FOR UPDATE` 按 (warehouse_id, sku_id) **升序固定顺序**加锁（防死锁），单据防并发（双击审核）用**条件状态 UPDATE**（`WHERE status=10`，影响 0 行即冲突）实现。

## Considered Options

- MyBatis-Plus @Version 乐观锁：低冲突场景可用，但库存事务涉及多表多行，version 冲突后的重试边界（哪些已写、哪些回滚）难以保证；并发 50 用户下悲观锁的吞吐代价可忽略。

## Consequences

- 所有动库存的代码必须走 StockService 门面（门面内统一加锁顺序），任何模块不得绕过直接 UPDATE inv_stock——ArchUnit 规则守护。
- 死锁预防依赖固定加锁顺序，新增多行库存操作时必须保持升序约定。
