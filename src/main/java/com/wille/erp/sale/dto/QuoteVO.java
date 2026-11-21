package com.wille.erp.sale.dto;

import com.wille.erp.sale.entity.SalQuote;
import com.wille.erp.sale.entity.SalQuoteItem;
import lombok.Data;

import java.util.List;

@Data
public class QuoteVO {
    private SalQuote doc;
    private List<SalQuoteItem> items;
}
