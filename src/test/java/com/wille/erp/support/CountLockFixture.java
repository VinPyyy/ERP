package com.wille.erp.support;

import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.inventory.entity.InvCount;
import com.wille.erp.inventory.entity.InvCountItem;
import com.wille.erp.inventory.mapper.InvCountItemMapper;
import com.wille.erp.inventory.mapper.InvCountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 盘点锁构造 fixture（ADR-0004 状态即锁）：M6 盘点单 UI 落地前，
 * 测试经此手工 INSERT COUNTING 盘点单覆盖指定仓库×SKU。
 */
@Component
@RequiredArgsConstructor
public class CountLockFixture {

    private final InvCountMapper countMapper;
    private final InvCountItemMapper countItemMapper;

    public Long counting(Long warehouseId, Long... skuIds) {
        InvCount count = new InvCount();
        count.setDocNo("PD-LOCK-" + System.nanoTime());
        count.setStatus(DocStatus.COUNTING);
        count.setDocDate(LocalDate.now());
        count.setCurrency("CNY");
        count.setWarehouseId(warehouseId);
        count.setFinFlag(0);
        countMapper.insert(count);
        int lineNo = 1;
        for (Long skuId : skuIds) {
            InvCountItem ci = new InvCountItem();
            ci.setDocId(count.getId());
            ci.setLineNo(lineNo++);
            ci.setSkuId(skuId);
            ci.setSkuName("锁定夹具");
            countItemMapper.insert(ci);
        }
        return count.getId();
    }
}
