package com.wille.erp.inventory.dto;

import com.wille.erp.inventory.core.ReplayDiff;

import java.util.List;

/** replay-check 报告：consistent=无任何差异；diffs 逐项定位（spec §3c 管理端接口） */
public record ReplayReportVO(boolean consistent, List<ReplayDiff> diffs) {
}
