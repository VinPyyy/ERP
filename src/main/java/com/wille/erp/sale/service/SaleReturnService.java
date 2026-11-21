package com.wille.erp.sale.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.CountLockGuard;
import com.wille.erp.inventory.api.LotApi;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockCmd.ReverseCmd;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.sale.dto.ReturnItemRequest;
import com.wille.erp.sale.dto.ReturnSaveRequest;
import com.wille.erp.sale.dto.ReturnVO;
import com.wille.erp.sale.entity.SalOutbound;
import com.wille.erp.sale.entity.SalOutboundItem;
import com.wille.erp.sale.entity.SalReturn;
import com.wille.erp.sale.entity.SalReturnItem;
import com.wille.erp.sale.mapper.SalOutboundItemMapper;
import com.wille.erp.sale.mapper.SalOutboundMapper;
import com.wille.erp.sale.mapper.SalReturnItemMapper;
import com.wille.erp.sale.mapper.SalReturnMapper;
import com.wille.erp.system.api.CurrentUserHolder;
import com.wille.erp.system.api.DocNoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 销售退货（spec §2.5/§8-3/§8-6）：有原单——价强制原出库价、SN 必须 ∈ 可退集合、
 * 累计退货（只计已审核）≤ 原出库量；无原单——价手录、批次仅定位既有、成本按当前均价。
 * 审核走门面 inbound（SN 翻回 IN_STOCK 由门面处理）；作废=reverse 出库方向回冲。
 */
@Service
@RequiredArgsConstructor
public class SaleReturnService {

    private final SalReturnMapper returnMapper;
    private final SalReturnItemMapper itemMapper;
    private final SalOutboundMapper outboundMapper;
    private final SalOutboundItemMapper outboundItemMapper;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final StockService stockService;
    private final CountLockGuard countLockGuard;
    private final LotApi lotApi;
    private final CurrentUserHolder currentUser;

    @Transactional(rollbackFor = Exception.class)
    public Long create(ReturnSaveRequest request) {
        requireWarehouseVisible(request.getWarehouseId());
        SalOutbound source = null;
        if (request.getSourceOutboundId() != null) {
            source = requireAuditedOutbound(request.getSourceOutboundId());
        }
        SalReturn doc = new SalReturn();
        doc.setDocNo(docNoService.nextNo(DocType.SAL_RETURN));
        doc.setStatus(DocStatus.DRAFT);
        doc.setDocDate(LocalDate.now());
        doc.setCurrency("CNY");
        doc.setCustomerId(request.getCustomerId());
        doc.setWarehouseId(request.getWarehouseId());
        doc.setSourceOutboundId(request.getSourceOutboundId());
        doc.setFinFlag(0);
        returnMapper.insert(doc);
        replaceItems(doc.getId(), request.getItems(), source);
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ReturnSaveRequest request) {
        SalReturn doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
        SalOutbound source = doc.getSourceOutboundId() == null
                ? null : requireAuditedOutbound(doc.getSourceOutboundId());
        replaceItems(id, request.getItems(), source);
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        SalReturn doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        int updated = returnMapper.update(null, Wrappers.<SalReturn>lambdaUpdate()
                .eq(SalReturn::getId, id).eq(SalReturn::getStatus, DocStatus.DRAFT)
                .set(SalReturn::getStatus, DocStatus.AUDITED)
                .set(SalReturn::getAuditBy, currentUser.userId())
                .set(SalReturn::getAuditTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        List<SalReturnItem> items = itemsOf(id);
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "退货单无明细行");
        }
        if (doc.getSourceOutboundId() != null) {
            requireAuditedOutbound(doc.getSourceOutboundId());
            for (SalReturnItem item : items) {
                requireSourceLine(item.getSourceOutboundItemId(), doc.getSourceOutboundId(), item.getSkuId());
            }
            // 配额串行化（审查 I1）：锁源出库行后再聚合，并发退货审核在行锁上排队
            items.stream().map(SalReturnItem::getSourceOutboundItemId).distinct()
                    .forEach(outboundItemMapper::selectForUpdate);
        }
        if (countLockGuard.counting(doc.getWarehouseId(),
                items.stream().map(SalReturnItem::getSkuId).toList())) {
            throw new BizException(ErrorCode.COUNT_LOCK_CONFLICT, "盘点中，禁止出入库审核");
        }
        checkReturnQuantities(items);
        List<MoveLine> lines = items.stream().map(item -> {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            if (item.getQty() == null || item.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "退货数量必须大于 0");
            }
            Long lotId = resolveLot(item, p);
            if (p.snManaged()) {
                requireIntegerQty(item.getQty(), "管SN商品退货数量必须为整数");
                if (item.getSnList() == null || item.getSnList().size() != item.getQty().intValueExact()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "管SN商品SN数量必须与退货量一致");
                }
                // §8-3：有原单退货的 SN 必须 ∈ 可退集合（OUT 且最近出库=源出库单）
                if (doc.getSourceOutboundId() != null) {
                    SalOutboundItem src = outboundItemMapper.selectById(item.getSourceOutboundItemId());
                    List<String> returnable = stockService.returnableSns(item.getSkuId(), item.getSnList(),
                            DocType.SAL_OUTBOUND, doc.getSourceOutboundId());
                    if (returnable.size() != item.getSnList().size()) {
                        throw new BizException(ErrorCode.PARAM_INVALID,
                                "SN 不在可退集合（须为原出库行且当前在外的 SN）: " + src.getSnList());
                    }
                }
            }
            return new MoveLine(item.getSkuId(), item.getQty(), item.getPrice(), lotId,
                    item.getSnList(), item.getId());
        }).toList();
        stockService.inbound(new MoveCmd(doc.getWarehouseId(),
                new BizRef(BizType.SAL_RETURN_IN, DocType.SAL_RETURN, doc.getId(), doc.getDocNo()), lines));
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidReturn(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因必填");
        }
        SalReturn doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        DocStatus from = doc.getStatus() == DocStatus.DRAFT ? DocStatus.DRAFT : DocStatus.AUDITED;
        int updated = returnMapper.update(null, Wrappers.<SalReturn>lambdaUpdate()
                .eq(SalReturn::getId, id).eq(SalReturn::getStatus, from)
                .set(SalReturn::getStatus, DocStatus.VOIDED)
                .set(SalReturn::getVoidBy, currentUser.userId())
                .set(SalReturn::getVoidTime, LocalDateTime.now())
                .set(SalReturn::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        if (from == DocStatus.DRAFT) {
            return;
        }
        stockService.reverse(new ReverseCmd(DocType.SAL_RETURN, id));
    }

    /** 可退 SN（spec §5 端点）：源出库行 sn_list ∩ 在外且最近出库指向该单；仓库数据权限（审查 I2） */
    public List<String> returnableSns(Long outboundItemId) {
        SalOutboundItem line = outboundItemMapper.selectById(outboundItemId);
        if (line == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "出库行不存在");
        }
        SalOutbound outbound = requireAuditedOutbound(line.getDocId());
        requireWarehouseVisible(outbound.getWarehouseId());
        return stockService.returnableSns(line.getSkuId(), line.getSnList(), DocType.SAL_OUTBOUND, line.getDocId());
    }

    /** 从出库单发起退货（spec §5 create-return）：按可退余量（原出库量 − 非作废退货累计）生成草稿 */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromOutbound(Long outboundId) {
        SalOutbound outbound = requireAuditedOutbound(outboundId);
        requireWarehouseVisible(outbound.getWarehouseId());
        List<SalOutboundItem> rows = outboundItemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, outboundId).orderByAsc(SalOutboundItem::getLineNo));
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "出库单无明细行");
        }
        Map<Long, BigDecimal> returned = sumReturnedBySourceLine(
                rows.stream().map(SalOutboundItem::getId).toList(), DocStatus.DRAFT, DocStatus.AUDITED);
        ReturnSaveRequest request = new ReturnSaveRequest();
        request.setCustomerId(outbound.getCustomerId());
        request.setWarehouseId(outbound.getWarehouseId());
        request.setSourceOutboundId(outboundId);
        List<ReturnItemRequest> lines = new ArrayList<>();
        for (SalOutboundItem row : rows) {
            BigDecimal remaining = row.getQty().subtract(returned.getOrDefault(row.getId(), BigDecimal.ZERO));
            if (remaining.signum() <= 0) {
                continue;
            }
            ReturnItemRequest line = new ReturnItemRequest();
            line.setSourceOutboundItemId(row.getId());
            line.setSkuId(row.getSkuId());
            line.setQty(remaining);
            line.setPrice(row.getPrice());
            line.setLotNo(row.getLotNo());
            line.setReason("出库单退货（待补充）");
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该出库单无剩余可退数量");
        }
        request.setItems(lines);
        return create(request);
    }

    public PageResult<SalReturn> page(int pageNum, int pageSize, String keyword) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && scope.isEmpty()) {
            PageResult<SalReturn> empty = new PageResult<>();
            empty.setTotal(0);
            empty.setRecords(List.of());
            return empty;
        }
        Page<SalReturn> page = returnMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<SalReturn>lambdaQuery()
                        .in(scope != null, SalReturn::getWarehouseId, scope == null ? Set.of() : scope)
                        .like(keyword != null && !keyword.isBlank(), SalReturn::getDocNo, keyword)
                        .orderByDesc(SalReturn::getId));
        return PageResult.of(page);
    }

    public ReturnVO get(Long id) {
        SalReturn doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        ReturnVO vo = new ReturnVO();
        vo.setDoc(doc);
        vo.setItems(itemsOf(id));
        return vo;
    }

    // ---------- 内部 ----------

    /** 有原单：累计退货（含本单，只计已审核）≤ 原出库量（§2.4 对称 + M4 I4 裁决） */
    private void checkReturnQuantities(List<SalReturnItem> items) {
        Map<Long, BigDecimal> bySourceLine = new HashMap<>();
        for (SalReturnItem item : items) {
            if (item.getSourceOutboundItemId() != null) {
                bySourceLine.merge(item.getSourceOutboundItemId(), item.getQty(), BigDecimal::add);
            }
        }
        if (bySourceLine.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> totals = sumReturnedBySourceLine(bySourceLine.keySet(), DocStatus.AUDITED);
        for (Map.Entry<Long, BigDecimal> entry : bySourceLine.entrySet()) {
            SalOutboundItem source = outboundItemMapper.selectById(entry.getKey());
            if (source == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "原出库行不存在");
            }
            BigDecimal already = totals.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (already.compareTo(source.getQty()) > 0) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "累计退货量超过原出库量: " + already.stripTrailingZeros().toPlainString()
                                + " > " + source.getQty().stripTrailingZeros().toPlainString());
            }
        }
    }

    private Map<Long, BigDecimal> sumReturnedBySourceLine(Collection<Long> sourceLineIds,
                                                          DocStatus... statuses) {
        List<Long> docIds = returnMapper.selectList(Wrappers.<SalReturn>lambdaQuery()
                        .in(SalReturn::getStatus, (Object[]) statuses))
                .stream().map(SalReturn::getId).toList();
        Map<Long, BigDecimal> totals = new HashMap<>();
        if (docIds.isEmpty()) {
            return totals;
        }
        itemMapper.selectList(Wrappers.<SalReturnItem>lambdaQuery()
                        .in(SalReturnItem::getDocId, docIds)
                        .in(SalReturnItem::getSourceOutboundItemId, sourceLineIds))
                .forEach(row -> totals.merge(row.getSourceOutboundItemId(), row.getQty(), BigDecimal::add));
        return totals;
    }

    private void replaceItems(Long docId, List<ReturnItemRequest> items, SalOutbound source) {
        itemMapper.delete(Wrappers.<SalReturnItem>lambdaQuery().eq(SalReturnItem::getDocId, docId));
        int lineNo = 1;
        for (ReturnItemRequest item : items) {
            if (item.getReason() == null || item.getReason().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "退货原因必填");
            }
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            BigDecimal price = item.getPrice();
            Long lotId = null;
            String lotNo = item.getLotNo();
            if (item.getSourceOutboundItemId() != null) {
                if (source == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "无源退货单不能携带原出库行");
                }
                SalOutboundItem src = requireSourceLine(item.getSourceOutboundItemId(),
                        source.getId(), item.getSkuId());
                price = src.getPrice(); // 有原单价强制（§8-6）
                lotId = src.getLotId(); // 批次以源行为准（M4 I5 对称）
                lotNo = src.getLotNo();
            }
            SalReturnItem row = new SalReturnItem();
            row.setDocId(docId);
            row.setLineNo(lineNo++);
            row.setSkuId(item.getSkuId());
            row.setSkuName(p.name());
            row.setUnit(p.unit());
            row.setSourceOutboundItemId(item.getSourceOutboundItemId());
            row.setQty(item.getQty());
            row.setPrice(price);
            row.setAmount(price == null ? null
                    : item.getQty().multiply(price).setScale(2, RoundingMode.HALF_UP));
            row.setLotId(lotId);
            row.setLotNo(lotNo);
            row.setSnList(item.getSnList());
            row.setReason(item.getReason());
            itemMapper.insert(row);
        }
    }

    private SalOutboundItem requireSourceLine(Long sourceItemId, Long outboundId, Long skuId) {
        SalOutboundItem src = outboundItemMapper.selectById(sourceItemId);
        if (src == null || !src.getDocId().equals(outboundId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "原出库行不存在或不属于该出库单");
        }
        if (!src.getSkuId().equals(skuId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "退货行商品与原出库行不一致");
        }
        return src;
    }

    private SalOutbound requireAuditedOutbound(Long outboundId) {
        SalOutbound source = outboundMapper.selectById(outboundId);
        if (source == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "原出库单不存在");
        }
        if (source.getStatus() != DocStatus.AUDITED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "原出库单须已审核（未作废）");
        }
        return source;
    }

    private Long resolveLot(SalReturnItem item, ProductReader.ProductInfo p) {
        boolean blankLot = item.getLotNo() == null || item.getLotNo().isBlank();
        if (blankLot) {
            if (p.lotManaged()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管批次商品必须填批号");
            }
            return null;
        }
        if (item.getLotId() != null) {
            return item.getLotId();
        }
        LotApi.LotView lot = lotApi.find(item.getSkuId(), item.getLotNo());
        if (lot == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "批次不存在: " + item.getLotNo() + "（退货不能新建批次）");
        }
        item.setLotId(lot.id());
        itemMapper.updateById(item);
        return lot.id();
    }

    private static void requireIntegerQty(BigDecimal qty, String message) {
        if (qty == null || qty.stripTrailingZeros().scale() > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
    }

    private void requireWarehouseVisible(Long warehouseId) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
    }

    private SalReturn requireDoc(Long id) {
        SalReturn doc = returnMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "退货单不存在");
        }
        return doc;
    }

    private List<SalReturnItem> itemsOf(Long docId) {
        return itemMapper.selectList(Wrappers.<SalReturnItem>lambdaQuery()
                .eq(SalReturnItem::getDocId, docId).orderByAsc(SalReturnItem::getLineNo));
    }
}
