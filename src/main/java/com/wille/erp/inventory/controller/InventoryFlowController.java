package com.wille.erp.inventory.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.wille.erp.common.api.R;
import com.wille.erp.inventory.dto.ReplayReportVO;
import com.wille.erp.inventory.service.StockReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 库存流水端点（M3a 先放对账；流水查询在 M3c 任务 8 加入） */
@RestController
@RequestMapping("/api/inventory/flows")
@RequiredArgsConstructor
public class InventoryFlowController {

    private final StockReconcileService reconcileService;

    /** 流水重放对账：diffs 为空即四层不变量零差异（spec §3c 管理端接口） */
    @GetMapping("/replay-check")
    @SaCheckPermission("inventory:flow:replay")
    public R<ReplayReportVO> replayCheck() {
        return R.ok(reconcileService.check());
    }
}
