package com.wille.erp.common.api;

import lombok.Data;

/**
 * 分页请求基类（spec §5.2）：pageNum 默认 1，pageSize 默认 20、上限 200。
 * 过滤字段由各业务 Query 子类平铺继承。排序固定，不开放通用 sort。
 */
@Data
public class PageQuery {

    public static final long MAX_PAGE_SIZE = 200;

    private long pageNum = 1;
    private long pageSize = 20;

    public long getPageNum() {
        return Math.max(pageNum, 1);
    }

    public long getPageSize() {
        return Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }
}
