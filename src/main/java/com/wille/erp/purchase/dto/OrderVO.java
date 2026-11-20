package com.wille.erp.purchase.dto;

import com.wille.erp.purchase.entity.PurOrder;
import com.wille.erp.purchase.entity.PurOrderItem;
import lombok.Data;

import java.util.List;

@Data
public class OrderVO {
    private PurOrder doc;
    private List<PurOrderItem> items;
}
