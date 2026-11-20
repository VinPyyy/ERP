package com.wille.erp.system.api;

import java.util.Set;

/**
 * 当前登录用户上下文（system 模块对外 API，spec §1）。
 * 业务模块经此获取操作人与数据权限范围，不直接触碰 Sa-Token。
 */
public interface CurrentUserHolder {

    /** 当前用户 id；未登录上下文（系统任务）返回 null */
    Long userId();

    String username();

    boolean hasPerm(String permCode);

    /**
     * 可见仓库 id 集（数据权限）：
     * 含 {@code 0L} = 全部仓库（含未来新增）；空集 = 看不到任何库存数据。
     */
    Set<Long> visibleWarehouseIds();

    /** 是否可见全部仓库 */
    boolean allWarehouses();

    /**
     * 仓库数据权限过滤：allWarehouses 返回 null（不过滤），
     * 否则返回可见仓库集合（可能为空集——调用方应查出空结果而非放行）。
     */
    default Set<Long> warehouseScope() {
        return allWarehouses() ? null : visibleWarehouseIds();
    }
}
