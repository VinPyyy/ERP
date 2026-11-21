package com.wille.erp.sale.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.wille.erp.common.api.R;
import com.wille.erp.sale.service.SaleAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 可用量查询：录入表单实时提示；权限=能建订单或出库单的人（OR 模式，方案 A 同源思路）。 */
@RestController
@RequestMapping("/api/sale")
@RequiredArgsConstructor
public class SaleAvailabilityController {

    private final SaleAvailabilityService availabilityService;

    @GetMapping("/availability")
    @SaCheckPermission(value = {"sale:order:create", "sale:outbound:create"}, mode = SaMode.OR)
    public R<Map<Long, BigDecimal>> availability(@RequestParam Long warehouseId,
                                                 @RequestParam List<Long> skuIds) {
        return R.ok(availabilityService.availability(warehouseId, skuIds));
    }
}
