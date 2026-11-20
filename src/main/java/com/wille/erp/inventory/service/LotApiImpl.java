package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.inventory.api.LotApi;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.mapper.InvLotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class LotApiImpl implements LotApi {

    private final InvLotMapper lotMapper;
    private final ProductReader productReader;

    @Override
    public Long materialize(Long skuId, String lotNo, LocalDate productionDate) {
        InvLot lot = lotMapper.selectOne(Wrappers.<InvLot>lambdaQuery()
                .eq(InvLot::getSkuId, skuId).eq(InvLot::getLotNo, lotNo));
        if (lot != null) {
            return lot.getId();
        }
        lot = new InvLot();
        lot.setSkuId(skuId);
        lot.setLotNo(lotNo);
        lot.setProductionDate(productionDate);
        if (productionDate != null) {
            ProductReader.ProductInfo p = productReader.read(skuId);
            if (p != null && p.shelfLifeDays() != null) {
                lot.setExpiryDate(productionDate.plusDays(p.shelfLifeDays()));
            }
        }
        lotMapper.insert(lot);
        return lot.getId();
    }
}
