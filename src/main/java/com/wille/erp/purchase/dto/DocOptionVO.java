package com.wille.erp.purchase.dto;

/**
 * 单据源单选项目（M4 审查后续，方案 A）：入库单选已审核订单、退货单选已审核入库单。
 * 交易数据非档案引用数据，权限用 OR 模式只放行"能创建下游单"的角色。
 */
public record DocOptionVO(Long id, String docNo, Long supplierId, Long warehouseId) {
}
