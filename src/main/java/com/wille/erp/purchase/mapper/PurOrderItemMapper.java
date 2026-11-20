package com.wille.erp.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.purchase.entity.PurOrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface PurOrderItemMapper extends BaseMapper<PurOrderItem> {

    /**
     * 溢收安全的原子累加（M4 审查 C1）：条件 {@code done_qty + delta <= limit} 由数据库判定，
     * 并发审核同订单行时后写无法覆盖先写、也无法突破上限；影响 0 行 = 超限/并发冲突，调用方拒单回滚。
     * MySQL 单条 UPDATE 内 SET 从左到右求值，line_status 的 CASE 读到的已是新 done_qty。
     */
    @Update("UPDATE pur_order_item SET done_qty = done_qty + #{delta}, "
            + "line_status = CASE WHEN line_status = 10 AND done_qty >= qty THEN 30 ELSE line_status END "
            + "WHERE id = #{id} AND deleted = 0 AND done_qty + #{delta} <= #{limit}")
    int addDoneQty(@Param("id") Long id, @Param("delta") BigDecimal delta, @Param("limit") BigDecimal limit);

    /** 作废回减：done≥qty 时行保持 30（M4 审查 I2——溢收后部分作废不误回退） */
    @Update("UPDATE pur_order_item SET done_qty = done_qty - #{delta}, "
            + "line_status = CASE WHEN line_status = 30 AND done_qty < qty THEN 10 ELSE line_status END "
            + "WHERE id = #{id} AND deleted = 0")
    int subDoneQty(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
