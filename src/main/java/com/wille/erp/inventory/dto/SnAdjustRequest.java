package com.wille.erp.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** SN 人工调整（spec §8-9）：仅状态/仓库 + 必填原因留痕；数量差由盘点修正 */
@Data
public class SnAdjustRequest {
    /** IN_STOCK / OUT */
    private String status;
    private Long warehouseId;
    @NotBlank(message = "调整原因必填")
    @Size(max = 255, message = "原因过长")
    private String reason;
}
