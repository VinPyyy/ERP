package com.wille.erp.inventory.dto;

import lombok.Data;

/** SN 人工调整（spec §8-9）：仅状态/仓库 + 必填原因留痕；数量差由盘点修正 */
@Data
public class SnAdjustRequest {
    /** IN_STOCK / OUT */
    private String status;
    private Long warehouseId;
    private String reason;
}
