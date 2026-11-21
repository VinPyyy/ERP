package com.wille.erp.sale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.sale.entity.SalOrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface SalOrderItemMapper extends BaseMapper<SalOrderItem> {

    /**
     * done_qty 原子累加（M4 审查 C1 同款纪律，销售无溢收：limit 恒 = qty，§8-5 超发严禁）。
     * 上限判定进 WHERE：并发出库同订单行后到者影响 0 行 → 调用方 50001 拒绝回滚。
     */
    @Update("UPDATE sal_order_item SET done_qty = done_qty + #{delta}, "
            + "line_status = CASE WHEN line_status = 10 AND done_qty >= qty THEN 30 ELSE line_status END "
            + "WHERE id = #{id} AND deleted = 0 AND done_qty + #{delta} <= qty")
    int addDoneQty(@Param("id") Long id, @Param("delta") BigDecimal delta);

    /** 出库作废回减：done≥qty 时行保持 30（I2 语义） */
    @Update("UPDATE sal_order_item SET done_qty = done_qty - #{delta}, "
            + "line_status = CASE WHEN line_status = 30 AND done_qty < qty THEN 10 ELSE line_status END "
            + "WHERE id = #{id} AND deleted = 0")
    int subDoneQty(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
