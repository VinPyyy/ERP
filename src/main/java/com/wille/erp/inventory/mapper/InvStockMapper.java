package com.wille.erp.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.inventory.entity.InvStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface InvStockMapper extends BaseMapper<InvStock> {

    /**
     * 全系统动库存唯一加锁姿势（ADR-0005）：调用方必须先按
     * (warehouse_id, sku_id) 升序排序再逐行取锁，防死锁。
     */
    @Select("select * from inv_stock where warehouse_id=#{warehouseId} and sku_id=#{skuId} and deleted=0 for update")
    InvStock selectForUpdate(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId);
}
