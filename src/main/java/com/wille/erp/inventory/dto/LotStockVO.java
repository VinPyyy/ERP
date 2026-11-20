package com.wille.erp.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class LotStockVO {
    private Long lotId;
    private String lotNo;
    private LocalDate productionDate;
    private LocalDate expiryDate;
    private BigDecimal qty;
}
