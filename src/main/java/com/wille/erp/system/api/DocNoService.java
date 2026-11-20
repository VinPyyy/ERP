package com.wille.erp.system.api;

import com.wille.erp.common.enums.DocType;

/**
 * 单据编号服务（system 模块对外 API）：{prefix}-{yyyyMMdd}-{4位流水}。
 * 取号 = 单事务行锁 + 期间重置；草稿创建时取号，作废不回收（跳号正常，ADR-0003）。
 */
public interface DocNoService {

    String nextNo(DocType docType);
}
