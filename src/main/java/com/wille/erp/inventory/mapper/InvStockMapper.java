package com.wille.erp.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.inventory.entity.InvStock;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface InvStockMapper extends BaseMapper<InvStock> {

    /**
     * 幂等占位（并发首插防死锁）：先 INSERT IGNORE 再 selectForUpdate。
     * 直接"查不到就插"会让两个事务在唯一索引间隙锁上互等（InnoDB gap lock 死锁）。
     */
    @Insert("insert ignore into inv_stock (warehouse_id, sku_id, qty, locked_qty, cost_price, cost_amount, deleted) "
            + "values (#{warehouseId}, #{skuId}, 0, 0, 0, 0, 0)")
    int ensureRow(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId);

    /**
     * 全系统动库存唯一加锁姿势（ADR-0005）：调用方必须先按
     * (warehouse_id, sku_id) 升序排序再逐行取锁，防死锁。
     */
    @Select("select * from inv_stock where warehouse_id=#{warehouseId} and sku_id=#{skuId} and deleted=0 for update")
    InvStock selectForUpdate(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId);
}
