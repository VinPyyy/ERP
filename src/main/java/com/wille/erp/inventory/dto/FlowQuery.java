package com.wille.erp.inventory.dto;

import com.wille.erp.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@EqualsAndHashCode(callSuper = true)
@Data
public class FlowQuery extends PageQuery {
    private Long skuId;
    private Long warehouseId;
    /** BizType 枚举名 */
    private String bizType;
    private String bizDocNo;
    private LocalDate startDate;
    private LocalDate endDate;
}
