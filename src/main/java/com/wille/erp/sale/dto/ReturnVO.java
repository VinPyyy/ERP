package com.wille.erp.sale.dto;

import com.wille.erp.sale.entity.SalReturn;
import com.wille.erp.sale.entity.SalReturnItem;
import lombok.Data;

import java.util.List;

@Data
public class ReturnVO {
    private SalReturn doc;
    private List<SalReturnItem> items;
}
