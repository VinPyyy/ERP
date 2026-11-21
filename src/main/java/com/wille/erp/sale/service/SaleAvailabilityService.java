package com.wille.erp.sale.service;

import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.system.api.CurrentUserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可用量查询（spec §5，M5 任务 1）：订单/出库录入实时提示 = qty − locked_qty。
 * 仓库数据权限：目标仓不可见即拒（NO_PERMISSION）。
 */
@Service
@RequiredArgsConstructor
public class SaleAvailabilityService {

    private final StockService stockService;
    private final CurrentUserHolder currentUser;

    public Map<Long, BigDecimal> availability(Long warehouseId, List<Long> skuIds) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (Long skuId : skuIds) {
            result.put(skuId, stockService.available(warehouseId, skuId));
        }
        return result;
    }
}
