package com.wille.erp.sale.dto;

import com.wille.erp.sale.entity.SalOrder;
import com.wille.erp.sale.entity.SalOrderItem;
import lombok.Data;

import java.util.List;

@Data
public class SaleOrderVO {
    private SalOrder doc;
    private List<SalOrderItem> items;
}
