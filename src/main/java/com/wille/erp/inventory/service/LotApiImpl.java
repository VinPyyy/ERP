package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.inventory.api.LotApi;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.mapper.InvLotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class LotApiImpl implements LotApi {

    private final InvLotMapper lotMapper;
    private final ProductReader productReader;

    @Override
    public Long materialize(Long skuId, String lotNo, LocalDate productionDate) {
        InvLot lot = selectBySkuLot(skuId, lotNo);
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
        try {
            lotMapper.insert(lot);
        } catch (DuplicateKeyException e) {
            // M4 审查 M4：并发物化撞 UNIQUE(sku_id,lot_no) → 复用他事务刚落库的行
            InvLot winner = selectBySkuLot(skuId, lotNo);
            if (winner == null) {
                throw e;
            }
            return winner.getId();
        }
        return lot.getId();
    }

    @Override
    public LotView find(Long skuId, String lotNo) {
        InvLot lot = selectBySkuLot(skuId, lotNo);
        return lot == null ? null : new LotView(lot.getId(), lot.getLotNo());
    }

    @Override
    public LotView byId(Long lotId) {
        InvLot lot = lotMapper.selectById(lotId);
        return lot == null ? null : new LotView(lot.getId(), lot.getLotNo());
    }

    private InvLot selectBySkuLot(Long skuId, String lotNo) {
        return lotMapper.selectOne(Wrappers.<InvLot>lambdaQuery()
                .eq(InvLot::getSkuId, skuId).eq(InvLot::getLotNo, lotNo));
    }
}
