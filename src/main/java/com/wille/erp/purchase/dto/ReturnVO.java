package com.wille.erp.purchase.dto;

import com.wille.erp.purchase.entity.PurReturn;
import com.wille.erp.purchase.entity.PurReturnItem;
import lombok.Data;

import java.util.List;

@Data
public class ReturnVO {
    private PurReturn doc;
    private List<PurReturnItem> items;
}
