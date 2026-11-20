package com.wille.erp.inventory.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.api.R;
import com.wille.erp.inventory.dto.FlowQuery;
import com.wille.erp.inventory.dto.FlowVO;
import com.wille.erp.inventory.dto.ReplayReportVO;
import com.wille.erp.inventory.service.InventoryQueryService;
import com.wille.erp.inventory.service.StockReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 库存流水端点：列表查询 + 重放对账 */
@RestController
@RequestMapping("/api/inventory/flows")
@RequiredArgsConstructor
public class InventoryFlowController {

    private final StockReconcileService reconcileService;
    private final InventoryQueryService queryService;

    /** 流水列表：多条件过滤 + 余额快照列 */
    @GetMapping
    @SaCheckPermission("inventory:flow:list")
    public R<PageResult<FlowVO>> flows(FlowQuery query) {
        return R.ok(queryService.pageFlows(query));
    }

    /** 流水重放对账：diffs 为空即四层不变量零差异（spec §3c 管理端接口） */
    @GetMapping("/replay-check")
    @SaCheckPermission("inventory:flow:replay")
    public R<ReplayReportVO> replayCheck() {
        return R.ok(reconcileService.check());
    }
}
