package com.wille.erp.purchase.dto;

import com.wille.erp.purchase.entity.PurInbound;
import com.wille.erp.purchase.entity.PurInboundItem;
import lombok.Data;

import java.util.List;

@Data
public class InboundVO {
    private PurInbound doc;
    private List<PurInboundItem> items;
}
