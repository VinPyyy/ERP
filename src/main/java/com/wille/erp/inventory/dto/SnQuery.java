package com.wille.erp.inventory.dto;

import com.wille.erp.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class SnQuery extends PageQuery {
    private Long skuId;
    private Long warehouseId;
    private Long lotId;
    /** IN_STOCK / OUT */
    private String status;
}
