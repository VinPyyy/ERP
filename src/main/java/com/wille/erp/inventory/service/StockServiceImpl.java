package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockCmd;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.core.AveragePriceCalculator;
import com.wille.erp.inventory.core.AveragePriceCalculator.InResult;
import com.wille.erp.inventory.core.AveragePriceCalculator.OutResult;
import com.wille.erp.inventory.core.StockState;
import com.wille.erp.inventory.entity.InvFlow;
import com.wille.erp.inventory.entity.InvLotStock;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvFlowMapper;
import com.wille.erp.inventory.mapper.InvLotMapper;
import com.wille.erp.inventory.mapper.InvLotStockMapper;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.system.api.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 全系统唯一动库存入口（spec §3a/§3b/§3f）。约束：
 * 单事务；行按 skuId 升序 FOR UPDATE（ADR-0005，cmd 内单仓）；
 * 均价走 AveragePriceCalculator 纯函数；每笔写 insert-only 流水含余额快照。
 */
@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private final InvStockMapper stockMapper;
    private final InvLotMapper lotMapper;
    private final InvLotStockMapper lotStockMapper;
    private final InvSnMapper snMapper;
    private final InvFlowMapper flowMapper;
    private final ProductReader productReader;
    private final ConfigService configService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void inbound(StockCmd.MoveCmd cmd) {
        sortedBySku(cmd.lines()).forEach(line -> move(cmd.warehouseId(), line, cmd.bizRef(), true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void outbound(StockCmd.MoveCmd cmd) {
        sortedBySku(cmd.lines()).forEach(line -> move(cmd.warehouseId(), line, cmd.bizRef(), false));
    }

    /** 固定加锁顺序防死锁（ADR-0005）：cmd 内单仓，按 skuId 升序即 (warehouse, sku) 升序 */
    private static List<StockCmd.MoveLine> sortedBySku(List<StockCmd.MoveLine> lines) {
        return lines.stream().sorted(Comparator.comparing(StockCmd.MoveLine::skuId)).toList();
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
        InvStock row = findRow(warehouseId, skuId);
        if (row == null) {
            return BigDecimal.ZERO;
        }
        return row.getQty().subtract(row.getLockedQty());
    }

    /** 单行变动：校验 → 锁行 → 纯函数 → 回写 → 批次/SN 分账 → 流水 */
    private void move(Long warehouseId, StockCmd.MoveLine line, StockCmd.BizRef ref, boolean inbound) {
        ProductReader.ProductInfo product = productReader.read(line.skuId());
        if (product == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + line.skuId());
        }
        validateShape(product, line);

        InvStock row = lockOrCreate(warehouseId, line.skuId());
        StockState before = state(row);
        if (!inbound) {
            validateOutbound(warehouseId, product, line, before, row);
        }

        StockState after;
        BigDecimal signedQty;
        BigDecimal signedCostAmount;
        BigDecimal flowCostPrice;
        if (inbound) {
            InResult r = AveragePriceCalculator.applyIn(before, line.qty(), line.price());
            after = r.next();
            signedQty = line.qty();
            signedCostAmount = r.costAmountDelta();
            flowCostPrice = line.price();
        } else {
            OutResult r = AveragePriceCalculator.applyOut(before, line.qty());
            after = r.next();
            signedQty = line.qty().negate();
            signedCostAmount = r.carryAmount().negate();
            flowCostPrice = before.costPrice();
        }
        writeBack(row, after);
        if (product.lotManaged()) {
            applyLotMovement(warehouseId, line, inbound);
        }
        if (product.snManaged()) {
            applySnMovement(warehouseId, line, ref, inbound);
        }
        writeFlow(warehouseId, line, ref, inbound, after, signedQty, signedCostAmount, flowCostPrice);
    }

    // ---------- 校验 ----------

    private void validateShape(ProductReader.ProductInfo product, StockCmd.MoveLine line) {
        if (line.qty() == null || line.qty().signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "变动数量必须大于 0");
        }
        boolean hasSn = line.snList() != null && !line.snList().isEmpty();
        if (product.lotManaged()) {
            if (line.lotId() == null || lotMapper.selectById(line.lotId()) == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管批次商品必须指定有效批次");
            }
        } else if (line.lotId() != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该商品不管理批次");
        }
        if (product.snManaged()) {
            if (line.qty().stripTrailingZeros().scale() > 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管SN商品的变动数量必须为整数");
            }
            if (!hasSn || line.snList().size() != line.qty().intValueExact()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管SN商品的SN数量必须与变动数量一致");
            }
        } else if (hasSn) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该商品不管理SN");
        }
    }

    private void validateOutbound(Long warehouseId, ProductReader.ProductInfo product,
                                  StockCmd.MoveLine line, StockState before, InvStock row) {
        if (!configService.getBool(ConfigService.KEY_ALLOW_NEGATIVE)) {
            BigDecimal available = before.qty().subtract(row.getLockedQty());
            if (available.compareTo(line.qty()) < 0) {
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                        "库存不足: 可用量 " + available.stripTrailingZeros().toPlainString());
            }
        }
        if (product.lotManaged()) {
            InvLotStock lotRow = findLotStock(warehouseId, line.skuId(), line.lotId());
            if (lotRow == null || lotRow.getQty().compareTo(line.qty()) < 0) {
                throw new BizException(ErrorCode.LOT_STOCK_NOT_ENOUGH, "批次库存不足");
            }
        }
        if (product.snManaged()) {
            for (String sn : line.snList()) {
                InvSn snRow = findSn(line.skuId(), sn);
                if (snRow == null || snRow.getStatus() != SnStatus.IN_STOCK
                        || !warehouseId.equals(snRow.getWarehouseId())) {
                    throw new BizException(ErrorCode.SN_STATUS_INVALID, "SN 不在当前仓库或在库状态不符: " + sn);
                }
            }
        }
    }

    // ---------- 主行 ----------

    /** INSERT IGNORE 占位再取锁：并发首插不死锁（间隙锁互等是"查不到就插"的固有缺陷） */
    private InvStock lockOrCreate(Long warehouseId, Long skuId) {
        stockMapper.ensureRow(warehouseId, skuId);
        InvStock row = stockMapper.selectForUpdate(warehouseId, skuId);
        if (row == null) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "库存行状态冲突，请重试");
        }
        return row;
    }

    private void writeBack(InvStock row, StockState after) {
        row.setQty(after.qty());
        row.setCostAmount(after.costAmount());
        row.setCostPrice(after.costPrice());
        stockMapper.updateById(row);
    }

    private static StockState state(InvStock row) {
        return new StockState(row.getQty(), row.getCostAmount(), row.getCostPrice());
    }

    // ---------- 批次 / SN ----------

    private void applyLotMovement(Long warehouseId, StockCmd.MoveLine line, boolean inbound) {
        lotStockMapper.ensureRow(warehouseId, line.skuId(), line.lotId());
        InvLotStock lotRow = findLotStock(warehouseId, line.skuId(), line.lotId());
        if (lotRow == null) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "批次行状态冲突，请重试");
        }
        BigDecimal delta = inbound ? line.qty() : line.qty().negate();
        lotRow.setQty(lotRow.getQty().add(delta));
        lotStockMapper.updateById(lotRow);
    }

    private void applySnMovement(Long warehouseId, StockCmd.MoveLine line,
                                 StockCmd.BizRef ref, boolean inbound) {
        for (String sn : line.snList()) {
            InvSn snRow = findSn(line.skuId(), sn);
            if (inbound) {
                if (snRow == null) {
                    InvSn fresh = new InvSn();
                    fresh.setSkuId(line.skuId());
                    fresh.setSnCode(sn);
                    fresh.setLotId(line.lotId());
                    applySnIn(fresh, warehouseId, ref);
                    snMapper.insert(fresh);
                } else if (snRow.getStatus() == SnStatus.IN_STOCK) {
                    throw new BizException(ErrorCode.SN_DUPLICATED_IN_STOCK, "SN 重复在库: " + sn);
                } else {
                    boolean returnIn = ref.bizType() == BizType.SAL_RETURN_IN;
                    snMapper.update(null, Wrappers.<InvSn>lambdaUpdate()
                            .eq(InvSn::getId, snRow.getId())
                            .set(InvSn::getStatus, SnStatus.IN_STOCK)
                            .set(InvSn::getWarehouseId, warehouseId)
                            // 退货回库清掉出库指向，避免"最近一次出库"误导追溯
                            .set(returnIn, InvSn::getOutDocType, null)
                            .set(returnIn, InvSn::getOutDocId, null)
                            .set(returnIn, InvSn::getOutDocNo, null)
                            .set(returnIn, InvSn::getOutTime, null)
                            .set(InvSn::getInDocType, ref.docType().name())
                            .set(InvSn::getInDocId, ref.docId())
                            .set(InvSn::getInDocNo, ref.docNo())
                            .set(InvSn::getInTime, LocalDateTime.now()));
                }
            } else {
                // updateById 忽略 null 字段，清空 warehouse_id 必须显式 set null
                snMapper.update(null, Wrappers.<InvSn>lambdaUpdate()
                        .eq(InvSn::getId, snRow.getId())
                        .set(InvSn::getStatus, SnStatus.OUT)
                        .set(InvSn::getWarehouseId, null)
                        .set(InvSn::getOutDocType, ref.docType().name())
                        .set(InvSn::getOutDocId, ref.docId())
                        .set(InvSn::getOutDocNo, ref.docNo())
                        .set(InvSn::getOutTime, LocalDateTime.now()));
            }
        }
    }

    private void applySnIn(InvSn snRow, Long warehouseId, StockCmd.BizRef ref) {
        snRow.setStatus(SnStatus.IN_STOCK);
        snRow.setWarehouseId(warehouseId);
        snRow.setInDocType(ref.docType().name());
        snRow.setInDocId(ref.docId());
        snRow.setInDocNo(ref.docNo());
        snRow.setInTime(LocalDateTime.now());
    }

    // ---------- 流水 ----------

    private void writeFlow(Long warehouseId, StockCmd.MoveLine line, StockCmd.BizRef ref, boolean inbound,
                           StockState after, BigDecimal signedQty, BigDecimal signedCostAmount,
                           BigDecimal flowCostPrice) {
        InvFlow flow = new InvFlow();
        flow.setWarehouseId(warehouseId);
        flow.setSkuId(line.skuId());
        flow.setLotId(line.lotId());
        flow.setQty(signedQty);
        flow.setPrice(line.price());
        flow.setCostPrice(flowCostPrice);
        flow.setCostAmount(signedCostAmount);
        flow.setBalanceQty(after.qty());
        flow.setBalanceCostAmount(after.costAmount());
        flow.setBalanceCostPrice(after.costPrice());
        flow.setBizType(ref.bizType());
        flow.setBizDocType(ref.docType());
        flow.setBizDocId(ref.docId());
        flow.setBizDocNo(ref.docNo());
        flow.setBizDocItemId(line.bizDocItemId());
        flowMapper.insert(flow);
    }

    // ---------- 查询辅助 ----------

    private InvStock findRow(Long warehouseId, Long skuId) {
        return stockMapper.selectOne(Wrappers.<InvStock>lambdaQuery()
                .eq(InvStock::getWarehouseId, warehouseId).eq(InvStock::getSkuId, skuId));
    }

    private InvLotStock findLotStock(Long warehouseId, Long skuId, Long lotId) {
        return lotStockMapper.selectOne(Wrappers.<InvLotStock>lambdaQuery()
                .eq(InvLotStock::getWarehouseId, warehouseId)
                .eq(InvLotStock::getSkuId, skuId)
                .eq(InvLotStock::getLotId, lotId));
    }

    private InvSn findSn(Long skuId, String snCode) {
        return snMapper.selectOne(Wrappers.<InvSn>lambdaQuery()
                .eq(InvSn::getSkuId, skuId).eq(InvSn::getSnCode, snCode));
    }
}
