package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.inventory.api.CountLockGuard;
import com.wille.erp.inventory.entity.InvCount;
import com.wille.erp.inventory.entity.InvCountItem;
import com.wille.erp.inventory.mapper.InvCountItemMapper;
import com.wille.erp.inventory.mapper.InvCountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 盘点锁 EXISTS 校验（spec §3d）：COUNTING 单头×行 SKU 集命中即锁。
 * 并发 ≤50、盘点单极少，成本可忽略。
 */
@Service
@RequiredArgsConstructor
public class CountLockGuardImpl implements CountLockGuard {

    private final InvCountMapper countMapper;
    private final InvCountItemMapper countItemMapper;

    @Override
    public boolean counting(Long warehouseId, Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return false;
        }
        List<Long> countingDocs = countMapper.selectList(Wrappers.<InvCount>lambdaQuery()
                        .eq(InvCount::getWarehouseId, warehouseId)
                        .eq(InvCount::getStatus, DocStatus.COUNTING))
                .stream().map(InvCount::getId).toList();
        if (countingDocs.isEmpty()) {
            return false;
        }
        return countItemMapper.selectCount(Wrappers.<InvCountItem>lambdaQuery()
                .in(InvCountItem::getDocId, countingDocs)
                .in(InvCountItem::getSkuId, skuIds)) > 0;
    }
}
