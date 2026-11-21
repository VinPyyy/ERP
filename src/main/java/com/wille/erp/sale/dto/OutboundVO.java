package com.wille.erp.sale.dto;

import com.wille.erp.sale.entity.SalOutbound;
import com.wille.erp.sale.entity.SalOutboundItem;
import lombok.Data;

import java.util.List;

@Data
public class OutboundVO {
    private SalOutbound doc;
    private List<SalOutboundItem> items;
}
