package com.wille.erp.sale.dto;

/** 销售源单下拉项（出库单选已审核订单；退货选已审核出库单） */
public record SaleDocOptionVO(Long id, String docNo, Long customerId, Long warehouseId) {
}
