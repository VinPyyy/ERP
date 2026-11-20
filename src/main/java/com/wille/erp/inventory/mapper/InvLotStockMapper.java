package com.wille.erp.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.inventory.entity.InvLotStock;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface InvLotStockMapper extends BaseMapper<InvLotStock> {

    /** 同 InvStockMapper.ensureRow：批次行也走 INSERT IGNORE 占位，主行锁已序列化同 (wh,sku) 的并发 */
    @Insert("insert ignore into inv_lot_stock (warehouse_id, sku_id, lot_id, qty, deleted) "
            + "values (#{warehouseId}, #{skuId}, #{lotId}, 0, 0)")
    int ensureRow(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId, @Param("lotId") Long lotId);
}
