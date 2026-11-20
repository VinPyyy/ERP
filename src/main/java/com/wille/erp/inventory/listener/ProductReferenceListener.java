package com.wille.erp.inventory.listener;

import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.event.ProductReferenceCheckEvent;
import com.wille.erp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商品引用校验监听器（spec §8-15）：库存主行/批次行/SN/流水任一有记录 → 禁删除、禁改管理维度。
 * M2 阶段库存实体层未建（M3 交付），用 JdbcTemplate 计数；M3 后不改——表结构与语义已定格。
 */
@Component
@RequiredArgsConstructor
public class ProductReferenceListener {

    private final JdbcTemplate jdbcTemplate;

    @EventListener
    public void onProductReferenceCheck(ProductReferenceCheckEvent event) {
        Long skuId = event.getSkuId();
        if (skuId == null) {
            return;
        }
        if (count("SELECT COUNT(*) FROM inv_stock WHERE sku_id = ? AND deleted = 0", skuId) > 0
                || count("SELECT COUNT(*) FROM inv_lot_stock WHERE sku_id = ? AND deleted = 0", skuId) > 0
                || count("SELECT COUNT(*) FROM inv_sn WHERE sku_id = ?", skuId) > 0
                || count("SELECT COUNT(*) FROM inv_flow WHERE sku_id = ? AND deleted = 0", skuId) > 0) {
            throw new BizException(ErrorCode.MGMT_MODE_LOCKED,
                    "商品 [" + event.getSkuCode() + "] 已产生库存/流水/单据引用，禁止删除或变更管理方式");
        }
    }

    private long count(String sql, Long skuId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, skuId);
        return value == null ? 0 : value;
    }
}
