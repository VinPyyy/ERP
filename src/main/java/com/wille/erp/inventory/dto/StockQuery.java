package com.wille.erp.inventory.dto;

import com.wille.erp.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class StockQuery extends PageQuery {
    /** 商品编码/名称模糊 */
    private String keyword;
    /** 与用户可见仓库 scope 取交集 */
    private Long warehouseId;
}
