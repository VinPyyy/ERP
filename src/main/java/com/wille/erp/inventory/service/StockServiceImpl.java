package com.wille.erp.inventory.service;

import com.wille.erp.inventory.api.StockCmd;
import com.wille.erp.inventory.api.StockService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 门面实现壳（任务 3/4 逐步填充：in/out → lock/unlock → reverse）。
 */
@Service
public class StockServiceImpl implements StockService {

    @Override
    public void inbound(StockCmd.MoveCmd cmd) {
        throw new UnsupportedOperationException("Task 3");
    }

    @Override
    public void outbound(StockCmd.MoveCmd cmd) {
        throw new UnsupportedOperationException("Task 3");
    }

    @Override
    public void lock(StockCmd.LockCmd cmd) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    public void unlock(StockCmd.LockRef ref) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    public void reverse(StockCmd.ReverseCmd cmd) {
        throw new UnsupportedOperationException("Task 6/M3b");
    }

    @Override
    public BigDecimal available(Long warehouseId, Long skuId) {
        throw new UnsupportedOperationException("Task 3");
    }
}
