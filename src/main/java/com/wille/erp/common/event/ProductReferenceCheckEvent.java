package com.wille.erp.common.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 商品引用校验事件：basedata 变更/删除商品时同步发布，
 * inventory（M2 起）与 purchase/sale（M4/M5 起）监听——存在库存/流水/单据引用则抛 BizException 阻断。
 * <p>
 * 方向合规：ArchUnit 禁止 basedata → inventory/purchase/sale，但反向监听允许；
 * 进程内同步事件异常原路传回，调用方事务整体回滚。
 */
@Getter
@RequiredArgsConstructor
public class ProductReferenceCheckEvent {

    private final Long skuId;
    /** 变更后的管理维度（null = 删除场景） */
    private final Integer targetMgmtModeCode;
    private final String skuCode;
}
