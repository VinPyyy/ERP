package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockCmd;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.core.AveragePriceCalculator;
import com.wille.erp.inventory.core.AveragePriceCalculator.InResult;
import com.wille.erp.inventory.core.AveragePriceCalculator.OutResult;
import com.wille.erp.inventory.core.StockState;
import com.wille.erp.inventory.entity.InvFlow;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.entity.InvLotStock;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.entity.InvStockLock;
import com.wille.erp.inventory.mapper.InvFlowMapper;
import com.wille.erp.inventory.mapper.InvLotMapper;
import com.wille.erp.inventory.mapper.InvLotStockMapper;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockLockMapper;
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
 *
 * 事务边界契约：写方法（inbound/outbound/lock/unlock/release/reverse）只能经 Spring 代理
 * 跨 bean 进入（外部注入调用），它们调用的私有辅助方法（move/lockOrCreate/writeBack/
 * applyLotDelta/applySnReverse/insertFlow 等）共享调用方事务、无需也无法自带事务；
 * 禁止在本类内新增 public @Transactional 方法并 this. 自调用（代理绕过、注解静默失效）。
 */
@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private final InvStockMapper stockMapper;
    private final InvLotMapper lotMapper;
    private final InvLotStockMapper lotStockMapper;
    private final InvSnMapper snMapper;
    private final InvStockLockMapper lockMapper;
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
    @Transactional(rollbackFor = Exception.class)
    public void lock(StockCmd.LockCmd cmd) {
        List<StockCmd.LockLine> lines = cmd.lines().stream()
                .sorted(Comparator.comparing(StockCmd.LockLine::skuId)).toList();
        for (StockCmd.LockLine line : lines) {
            // 负数/零锁定会静默击穿可用量且骗过对账器（审查 Important-8）
            if (line.qty() == null || line.qty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "锁定数量必须大于 0");
            }
            InvStock row = lockOrCreate(cmd.warehouseId(), line.skuId());
            if (!configService.getBool(ConfigService.KEY_ALLOW_NEGATIVE)) {
                BigDecimal available = row.getQty().subtract(row.getLockedQty());
                if (available.compareTo(line.qty()) < 0) {
                    throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                            "可用量不足: " + available.stripTrailingZeros().toPlainString());
                }
            }
            InvStockLock lock = new InvStockLock();
            lock.setLockDocType(cmd.ref().lockDocType().name());
            lock.setLockDocId(cmd.ref().lockDocId());
            lock.setLockItemId(cmd.ref().lockItemId());
            lock.setWarehouseId(cmd.warehouseId());
            lock.setSkuId(line.skuId());
            lock.setLockQty(line.qty());
            lock.setRemainQty(line.qty());
            lockMapper.insert(lock);
            row.setLockedQty(row.getLockedQty().add(line.qty()));
            stockMapper.updateById(row);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlock(StockCmd.LockRef ref) {
        List<InvStockLock> rows = lockMapper.selectList(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getLockDocType, ref.lockDocType().name())
                .eq(InvStockLock::getLockDocId, ref.lockDocId())
                .eq(InvStockLock::getLockItemId, ref.lockItemId())
                .gt(InvStockLock::getRemainQty, BigDecimal.ZERO));
        // 固定加锁顺序（审查 Important-2）：多行 ref 乱序锁 inv_stock 会与 lock/move 交叉 AB-BA
        rows = rows.stream()
                .sorted(Comparator.comparing(InvStockLock::getWarehouseId)
                        .thenComparing(InvStockLock::getSkuId))
                .toList();
        for (InvStockLock lock : rows) {
            InvStock row = lockOrCreate(lock.getWarehouseId(), lock.getSkuId());
            row.setLockedQty(row.getLockedQty().subtract(lock.getRemainQty()));
            stockMapper.updateById(row);
            // remain=0 行保留作历史
            lock.setRemainQty(BigDecimal.ZERO);
            lockMapper.updateById(lock);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(StockCmd.LockRef ref, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "核销数量必须大于 0");
        }
        List<InvStockLock> rows = lockMapper.selectList(Wrappers.<InvStockLock>lambdaQuery()
                .eq(InvStockLock::getLockDocType, ref.lockDocType().name())
                .eq(InvStockLock::getLockDocId, ref.lockDocId())
                .eq(InvStockLock::getLockItemId, ref.lockItemId())
                .gt(InvStockLock::getRemainQty, BigDecimal.ZERO));
        if (rows.size() != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "锁定明细不存在或已核销完毕");
        }
        InvStockLock lock = rows.get(0);
        if (lock.getRemainQty().compareTo(qty) < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "核销数量超过剩余锁定");
        }
        InvStock row = lockOrCreate(lock.getWarehouseId(), lock.getSkuId());
        lock.setRemainQty(lock.getRemainQty().subtract(qty));
        row.setLockedQty(row.getLockedQty().subtract(qty));
        lockMapper.updateById(lock);
        stockMapper.updateById(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reverse(StockCmd.ReverseCmd cmd) {
        // 作废冲正（ADR-0003）：金额一律取原流水快照原路回冲，绝不反算、绝不用当前均价冲历史
        List<InvFlow> originals = flowMapper.selectList(Wrappers.<InvFlow>lambdaQuery()
                .eq(InvFlow::getBizDocType, cmd.docType())
                .eq(InvFlow::getBizDocId, cmd.docId())
                .isNull(InvFlow::getReverseFlowId)
                .orderByAsc(InvFlow::getId));
        for (InvFlow f : originals) {
            // 防重复冲正（spec §3f reverse_flow_id 用途，审查 I1）：原流水已有冲正行指向它即拒绝
            if (flowMapper.selectCount(Wrappers.<InvFlow>lambdaQuery()
                    .eq(InvFlow::getReverseFlowId, f.getId())) > 0) {
                throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "该单据流水已被冲正");
            }
            InvStock row = lockOrCreate(f.getWarehouseId(), f.getSkuId());
            StockState before = state(row);
            BigDecimal revQty = f.getQty().negate();
            BigDecimal revAmount = f.getCostAmount().negate();
            // 入库类冲正前置校验（spec §3c 第 2 条）：冲减后可用量不得为负
            if (f.getQty().signum() > 0
                    && before.qty().subtract(row.getLockedQty()).compareTo(f.getQty()) < 0) {
                throw new BizException(ErrorCode.VOID_STOCK_CONSUMED,
                        "库存已被后续单据消耗，请先作废下游单据");
            }
            BigDecimal qty = before.qty().add(revQty);
            BigDecimal amount = before.costAmount().add(revAmount).setScale(2, java.math.RoundingMode.HALF_UP);
            StockState after = new StockState(qty, amount,
                    AveragePriceCalculator.derivePrice(amount, qty));
            writeBack(row, after);
            if (f.getLotId() != null) {
                applyLotDelta(f.getWarehouseId(), f.getSkuId(), f.getLotId(), revQty);
            }
            applySnReverse(f, cmd);
            insertFlow(f.getWarehouseId(), f.getSkuId(), f.getLotId(), f.getPrice(), f.getCostPrice(),
                    revQty, revAmount, after, f.getBizType(), f.getBizDocType(), f.getBizDocId(),
                    f.getBizDocNo(), f.getBizDocItemId(), f.getId());
        }
    }

    private void applyLotDelta(Long warehouseId, Long skuId, Long lotId, BigDecimal delta) {
        lotStockMapper.ensureRow(warehouseId, skuId, lotId);
        InvLotStock lotRow = findLotStock(warehouseId, skuId, lotId);
        if (lotRow == null) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "批次行状态冲突，请重试");
        }
        lotRow.setQty(lotRow.getQty().add(delta));
        lotStockMapper.updateById(lotRow);
    }

    /**
     * SN 冲正（spec §4.2 步骤5）：入库类删除本单新建的 SN——物理删除，
     * 软删行会占住 UNIQUE(sku,sn_code) 导致同 SN 永远无法再入库（用户名同款坑，裁决记录账本）；
     * 出库类把 out_doc 指向本单的 SN 翻回 IN_STOCK（M4/M5 用例覆盖）。
     */
    private void applySnReverse(InvFlow f, StockCmd.ReverseCmd cmd) {
        if (f.getQty().signum() > 0) {
            // 新建的（从未出库）物理删除；曾出库被本单翻回的恢复 OUT 态（审查 C1）
            snMapper.hardDeleteByInDoc(f.getSkuId(), cmd.docType().name(), cmd.docId());
            snMapper.update(null, Wrappers.<InvSn>lambdaUpdate()
                    .eq(InvSn::getSkuId, f.getSkuId())
                    .eq(InvSn::getInDocType, cmd.docType().name())
                    .eq(InvSn::getInDocId, cmd.docId())
                    .isNotNull(InvSn::getOutDocId)
                    .set(InvSn::getStatus, SnStatus.OUT)
                    .set(InvSn::getWarehouseId, null)
                    .set(InvSn::getInDocType, null)
                    .set(InvSn::getInDocId, null)
                    .set(InvSn::getInDocNo, null)
                    .set(InvSn::getInTime, null));
        } else {
            snMapper.update(null, Wrappers.<InvSn>lambdaUpdate()
                    .eq(InvSn::getSkuId, f.getSkuId())
                    .eq(InvSn::getOutDocType, cmd.docType().name())
                    .eq(InvSn::getOutDocId, cmd.docId())
                    .set(InvSn::getStatus, SnStatus.IN_STOCK)
                    .set(InvSn::getWarehouseId, f.getWarehouseId())
                    .set(InvSn::getOutDocType, null)
                    .set(InvSn::getOutDocId, null)
                    .set(InvSn::getOutDocNo, null)
                    .set(InvSn::getOutTime, null));
        }
    }

    @Override
    public BigDecimal available(Long warehouseId, Long skuId) {
        InvStock row = findRow(warehouseId, skuId);
        if (row == null) {
            return BigDecimal.ZERO;
        }
        return row.getQty().subtract(row.getLockedQty());
    }

    @Override
    public List<String> returnableSns(Long skuId, List<String> candidates,
                                      DocType outDocType, Long outDocId) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return snMapper.selectList(Wrappers.<InvSn>lambdaQuery()
                        .eq(InvSn::getSkuId, skuId)
                        .in(InvSn::getSnCode, candidates)
                        .eq(InvSn::getStatus, SnStatus.OUT)
                        .eq(InvSn::getOutDocType, outDocType.name())
                        .eq(InvSn::getOutDocId, outDocId))
                .stream().map(InvSn::getSnCode).toList();
    }

    /** 单行变动：校验 → 锁行 → 纯函数 → 回写 → 批次/SN 分账 → 流水 */
    private void move(Long warehouseId, StockCmd.MoveLine line, StockCmd.BizRef ref, boolean inbound) {
        ProductReader.ProductInfo product = productReader.read(line.skuId());
        if (product == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + line.skuId());
        }
        validateShape(product, line, inbound);

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

    private void validateShape(ProductReader.ProductInfo product, StockCmd.MoveLine line, boolean inbound) {
        if (line.qty() == null || line.qty().signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "变动数量必须大于 0");
        }
        if (inbound && line.price() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "入库必须携带入账价格");
        }
        boolean hasSn = line.snList() != null && !line.snList().isEmpty();
        if (product.lotManaged()) {
            if (line.lotId() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管批次商品必须指定批次");
            }
            InvLot lot = lotMapper.selectById(line.lotId());
            if (lot == null || !lot.getSkuId().equals(line.skuId())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "批次不存在或不属于该商品");
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
                // §3b：批次+SN 模式出库须批次一致，否则 SN-批次对应关系静默损坏（审查 Important-6）
                if (product.lotManaged() && !line.lotId().equals(snRow.getLotId())) {
                    throw new BizException(ErrorCode.SN_STATUS_INVALID, "SN 批次与出库行不一致: " + sn);
                }
            }
        }
    }

    // ---------- 主行 ----------

    /** INSERT IGNORE 占位再取锁（仅首插路径）：行已存在时直接 FOR UPDATE——
     *  无条件 INSERT IGNORE 撞已存在唯一键会持 S 锁到事务结束，双事务各持 S 求 X 必死锁（审查 Important-1） */
    private InvStock lockOrCreate(Long warehouseId, Long skuId) {
        if (findRow(warehouseId, skuId) == null) {
            stockMapper.ensureRow(warehouseId, skuId);
        }
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
                    // 翻回保留 out_doc_*：既是"最近一次出库"（§3b），也是冲正时区分新建/翻回的判据（审查 C1 裁决）
                    snMapper.update(null, Wrappers.<InvSn>lambdaUpdate()
                            .eq(InvSn::getId, snRow.getId())
                            .set(InvSn::getStatus, SnStatus.IN_STOCK)
                            .set(InvSn::getWarehouseId, warehouseId)
                            .set(InvSn::getLotId, line.lotId())
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
        insertFlow(warehouseId, line.skuId(), line.lotId(), line.price(), flowCostPrice,
                signedQty, signedCostAmount, after, ref.bizType(), ref.docType(), ref.docId(),
                ref.docNo(), line.bizDocItemId(), null);
    }

    private void insertFlow(Long warehouseId, Long skuId, Long lotId, BigDecimal price, BigDecimal costPrice,
                            BigDecimal signedQty, BigDecimal signedCostAmount, StockState after,
                            BizType bizType, DocType docType, Long docId, String docNo,
                            Long bizDocItemId, Long reverseFlowId) {
        InvFlow flow = new InvFlow();
        flow.setWarehouseId(warehouseId);
        flow.setSkuId(skuId);
        flow.setLotId(lotId);
        flow.setQty(signedQty);
        flow.setPrice(price);
        flow.setCostPrice(costPrice);
        flow.setCostAmount(signedCostAmount);
        flow.setBalanceQty(after.qty());
        flow.setBalanceCostAmount(after.costAmount());
        flow.setBalanceCostPrice(after.costPrice());
        flow.setBizType(bizType);
        flow.setBizDocType(docType);
        flow.setBizDocId(docId);
        flow.setBizDocNo(docNo);
        flow.setBizDocItemId(bizDocItemId);
        flow.setReverseFlowId(reverseFlowId);
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
