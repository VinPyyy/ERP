package com.wille.erp.inventory.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.inventory.dto.LotStockVO;
import com.wille.erp.inventory.dto.SnAdjustRequest;
import com.wille.erp.inventory.dto.SnQuery;
import com.wille.erp.inventory.dto.SnVO;
import com.wille.erp.inventory.dto.StockQuery;
import com.wille.erp.inventory.dto.StockVO;
import com.wille.erp.inventory.service.InventoryQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 库存三级查询 + SN 人工调整（spec §5 库存端点） */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryQueryController {

    private final InventoryQueryService queryService;

    /** 汇总级：仓库×SKU + 可用量；成本列按 inventory:cost:view 脱敏 */
    @GetMapping("/stocks")
    @SaCheckPermission("inventory:stock:list")
    public R<PageResult<StockVO>> stocks(StockQuery query) {
        return R.ok(queryService.pageStocks(query));
    }

    /** 批次级下钻 */
    @GetMapping("/stocks/lots")
    @SaCheckPermission("inventory:stock:list")
    public R<List<LotStockVO>> lots(@RequestParam Long warehouseId, @RequestParam Long skuId) {
        return R.ok(queryService.lotStocks(warehouseId, skuId));
    }

    /** SN 级下钻 / 查询 */
    @GetMapping("/sns")
    @SaCheckPermission("inventory:stock:list")
    public R<PageResult<SnVO>> sns(SnQuery query) {
        return R.ok(queryService.pageSns(query));
    }

    /** SN 人工调整：原因必填留痕（spec §8-9） */
    @PostMapping("/sns/{id}/adjust")
    @SaCheckPermission("inventory:sn:adjust")
    public R<Void> adjustSn(@PathVariable Long id, @Valid @RequestBody SnAdjustRequest request) {
        queryService.adjustSn(id, request);
        return R.ok();
    }
}
