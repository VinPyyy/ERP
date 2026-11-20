package com.wille.erp.basedata.api;

import java.util.Set;

/**
 * 仓库只读门面（basedata 对外 API）：单据审核校验仓库有效性 + 启停用状态。
 */
public interface WarehouseReader {

    boolean existsActive(Long warehouseId);

    /** 批量校验：返回不存在或已停用的仓库 id 集（空集 = 全部有效） */
    Set<Long> findInvalid(Set<Long> warehouseIds);
}
