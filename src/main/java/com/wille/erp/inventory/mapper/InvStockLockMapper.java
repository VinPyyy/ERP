package com.wille.erp.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.inventory.entity.InvStockLock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface InvStockLockMapper extends BaseMapper<InvStockLock> {

    /**
     * 核销原子递减（M5 审查 C3）：remain 判定进 WHERE，
     * 并发核销同锁行时后到者读到陈旧 remain 也无法超扣（0 行=超剩余，调用方拒绝回滚）。
     */
    @Update("UPDATE inv_stock_lock SET remain_qty = remain_qty - #{qty} "
            + "WHERE id = #{id} AND deleted = 0 AND remain_qty >= #{qty}")
    int releaseRemain(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /** 作废重锁原子恢复（M5 审查 C2）：恢复原行 remain，杜绝同 ref 出现多条活跃锁行 */
    @Update("UPDATE inv_stock_lock SET remain_qty = remain_qty + #{qty} "
            + "WHERE id = #{id} AND deleted = 0")
    int restoreRemain(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /** 释放原子清零（乐观条件：仅当 remain 仍等于读值） */
    @Update("UPDATE inv_stock_lock SET remain_qty = 0 "
            + "WHERE id = #{id} AND deleted = 0 AND remain_qty = #{expected}")
    int zeroRemain(@Param("id") Long id, @Param("expected") BigDecimal expected);
}
