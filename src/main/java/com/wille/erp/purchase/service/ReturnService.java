package com.wille.erp.purchase.service;

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
import com.wille.erp.purchase.dto.ReturnItemRequest;
import com.wille.erp.purchase.dto.ReturnSaveRequest;
import com.wille.erp.purchase.dto.ReturnVO;
import com.wille.erp.purchase.entity.PurInbound;
import com.wille.erp.purchase.entity.PurInboundItem;
import com.wille.erp.purchase.entity.PurReturn;
import com.wille.erp.purchase.entity.PurReturnItem;
import com.wille.erp.purchase.mapper.PurInboundItemMapper;
import com.wille.erp.purchase.mapper.PurInboundMapper;
import com.wille.erp.purchase.mapper.PurReturnItemMapper;
import com.wille.erp.purchase.mapper.PurReturnMapper;
import com.wille.erp.system.api.CurrentUserHolder;
import com.wille.erp.system.api.DocNoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 采购退货（出库方向，spec §2.4/§8-6）：有原单→价强制原单价、累计退货（只计已审核，M4 审查 I4）≤ 原入库量（40002）；
 * 无原单→价手录、管批次按既有批号定位（出库禁造批次，审查 I5）；成本一律当前均价（门面结转）；
 * SN 经门面核销出库；作废=reverse 出库方向回冲；草稿亦可直接作废（审查 I6）。
 */
@Service
@RequiredArgsConstructor
public class ReturnService {

    private final PurReturnMapper returnMapper;
    private final PurReturnItemMapper itemMapper;
    private final PurInboundMapper inboundMapper;
    private final PurInboundItemMapper inboundItemMapper;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final StockService stockService;
    private final CountLockGuard countLockGuard;
    private final LotApi lotApi;
    private final CurrentUserHolder currentUser;

    @Transactional(rollbackFor = Exception.class)
    public Long create(ReturnSaveRequest request) {
        requireWarehouseVisible(request.getWarehouseId());
        PurInbound source = null;
        if (request.getSourceInboundId() != null) {
            source = requireAuditedInbound(request.getSourceInboundId());
            if (!source.getWarehouseId().equals(request.getWarehouseId())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "带源单的退货单仓库必须与原入库单一致");
            }
        }
        PurReturn doc = new PurReturn();
        doc.setDocNo(docNoService.nextNo(DocType.PUR_RETURN));
        doc.setStatus(DocStatus.DRAFT);
        doc.setDocDate(LocalDate.now());
        doc.setCurrency("CNY");
        doc.setSupplierId(request.getSupplierId());
        doc.setWarehouseId(request.getWarehouseId());
        doc.setSourceInboundId(request.getSourceInboundId());
        doc.setFinFlag(0);
        returnMapper.insert(doc);
        replaceItems(doc.getId(), request.getItems(), source);
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ReturnSaveRequest request) {
        PurReturn doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
        PurInbound source = doc.getSourceInboundId() == null
                ? null : requireAuditedInbound(doc.getSourceInboundId());
        replaceItems(id, request.getItems(), source);
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        PurReturn doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        int updated = returnMapper.update(null, Wrappers.<PurReturn>lambdaUpdate()
                .eq(PurReturn::getId, id).eq(PurReturn::getStatus, DocStatus.DRAFT)
                .set(PurReturn::getStatus, DocStatus.AUDITED)
                .set(PurReturn::getAuditBy, currentUser.userId())
                .set(PurReturn::getAuditTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        List<PurReturnItem> items = itemsOf(id);
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "退货单无明细行");
        }
        // M4 审查 I3：审核时复核源单状态与行归属（保存后源单被作废/行被篡改在这里兜底）
        if (doc.getSourceInboundId() != null) {
            requireAuditedInbound(doc.getSourceInboundId());
            for (PurReturnItem item : items) {
                requireSourceLine(item.getSourceInboundItemId(), doc.getSourceInboundId(), item.getSkuId());
            }
        }
        if (countLockGuard.counting(doc.getWarehouseId(),
                items.stream().map(PurReturnItem::getSkuId).toList())) {
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
            if (p.snManaged()) {
                requireIntegerQty(item.getQty(), "管SN商品退货数量必须为整数");
                if (item.getSnList() == null || item.getSnList().size() != item.getQty().intValueExact()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "管SN商品SN数量必须与退货量一致");
                }
            }
            Long lotId = resolveLotId(item, p);
            return new MoveLine(item.getSkuId(), item.getQty(), item.getPrice(), lotId,
                    item.getSnList(), item.getId());
        }).toList();
        stockService.outbound(new MoveCmd(doc.getWarehouseId(),
                new BizRef(BizType.PUR_RETURN_OUT, DocType.PUR_RETURN, doc.getId(), doc.getDocNo()), lines));
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidReturn(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因必填");
        }
        PurReturn doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        // M4 审查 I6：草稿直接作废，不动库存
        DocStatus from = doc.getStatus() == DocStatus.DRAFT ? DocStatus.DRAFT : DocStatus.AUDITED;
        int updated = returnMapper.update(null, Wrappers.<PurReturn>lambdaUpdate()
                .eq(PurReturn::getId, id).eq(PurReturn::getStatus, from)
                .set(PurReturn::getStatus, DocStatus.VOIDED)
                .set(PurReturn::getVoidBy, currentUser.userId())
                .set(PurReturn::getVoidTime, LocalDateTime.now())
                .set(PurReturn::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        if (from == DocStatus.DRAFT) {
            return;
        }
        stockService.reverse(new ReverseCmd(DocType.PUR_RETURN, id));
    }

    /**
     * 从入库单发起退货（M4 审查 I7，spec §5 端点）：按可退余量（原入库量 − 非作废退货累计）生成草稿。
     * 余量展示把 DRAFT 退货也扣掉，防重复发起；权威拦截在审核（只计已审核）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromInbound(Long inboundId) {
        PurInbound inbound = requireAuditedInbound(inboundId);
        requireWarehouseVisible(inbound.getWarehouseId());
        List<PurInboundItem> rows = inboundItemMapper.selectList(Wrappers.<PurInboundItem>lambdaQuery()
                .eq(PurInboundItem::getDocId, inboundId).orderByAsc(PurInboundItem::getLineNo));
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "入库单无明细行");
        }
        Map<Long, BigDecimal> returned = sumReturnedBySourceLine(rows.stream().map(PurInboundItem::getId).toList(),
                DocStatus.DRAFT, DocStatus.AUDITED);
        ReturnSaveRequest request = new ReturnSaveRequest();
        request.setSupplierId(inbound.getSupplierId());
        request.setWarehouseId(inbound.getWarehouseId());
        request.setSourceInboundId(inboundId);
        List<ReturnItemRequest> lines = rows.stream()
                .map(row -> {
                    BigDecimal remaining = row.getQty().subtract(
                            returned.getOrDefault(row.getId(), BigDecimal.ZERO));
                    if (remaining.signum() <= 0) {
                        return null;
                    }
                    ReturnItemRequest line = new ReturnItemRequest();
                    line.setSourceInboundItemId(row.getId());
                    line.setSkuId(row.getSkuId());
                    line.setQty(remaining);
                    line.setPrice(row.getPrice());
                    line.setLotNo(row.getLotNo());
                    line.setReason("入库单退货（待补充）");
                    return line;
                })
                .filter(Objects::nonNull)
                .toList();
        if (lines.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该入库单无剩余可退数量");
        }
        request.setItems(lines);
        return create(request);
    }

    /** 有原单：累计退货（本单已置 AUDITED、只计已审核单、不含作废）≤ 原入库量（spec §2.4 + 审查 I4） */
    private void checkReturnQuantities(List<PurReturnItem> items) {
        Map<Long, BigDecimal> bySourceLine = new HashMap<>();
        for (PurReturnItem item : items) {
            if (item.getSourceInboundItemId() != null) {
                bySourceLine.merge(item.getSourceInboundItemId(), item.getQty(), BigDecimal::add);
            }
        }
        if (bySourceLine.isEmpty()) {
            return;
        }
        // 聚合一次查（审查 I4 的 N+1 全表扫描修复）：状态 AUDITED 且非本单已含（本单已置 AUDITED）
        Map<Long, BigDecimal> totals = sumReturnedBySourceLine(bySourceLine.keySet(), DocStatus.AUDITED);
        for (Map.Entry<Long, BigDecimal> entry : bySourceLine.entrySet()) {
            PurInboundItem source = inboundItemMapper.selectById(entry.getKey());
            if (source == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "原入库行不存在");
            }
            BigDecimal already = totals.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (already.compareTo(source.getQty()) > 0) {
                throw new BizException(ErrorCode.RETURN_EXCEED_SOURCE,
                        "累计退货量超过原入库量: " + already.stripTrailingZeros().toPlainString()
                                + " > " + source.getQty().stripTrailingZeros().toPlainString());
            }
        }
    }

    private Map<Long, BigDecimal> sumReturnedBySourceLine(Collection<Long> sourceLineIds, DocStatus... statuses) {
        List<Long> docIds = returnMapper.selectList(Wrappers.<PurReturn>lambdaQuery()
                        .in(PurReturn::getStatus, (Object[]) statuses))
                .stream().map(PurReturn::getId).toList();
        Map<Long, BigDecimal> totals = new HashMap<>();
        if (docIds.isEmpty()) {
            return totals;
        }
        itemMapper.selectList(Wrappers.<PurReturnItem>lambdaQuery()
                        .in(PurReturnItem::getDocId, docIds)
                        .in(PurReturnItem::getSourceInboundItemId, sourceLineIds))
                .forEach(row -> totals.merge(row.getSourceInboundItemId(), row.getQty(), BigDecimal::add));
        return totals;
    }

    private void replaceItems(Long docId, List<ReturnItemRequest> items, PurInbound source) {
        itemMapper.delete(Wrappers.<PurReturnItem>lambdaQuery().eq(PurReturnItem::getDocId, docId));
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
            if (item.getSourceInboundItemId() != null) {
                if (source == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "无源退货单不能携带源入库行");
                }
                // M4 审查 I3：行归属 + SKU 一致性；价强制原单价（spec §2.4）
                PurInboundItem src = requireSourceLine(item.getSourceInboundItemId(), source.getId(), item.getSkuId());
                price = src.getPrice();
                // M4 审查 I5：批号/批次以源行为准，用户输入被服务端覆盖
                lotId = src.getLotId();
                lotNo = src.getLotNo();
            }
            PurReturnItem row = new PurReturnItem();
            row.setDocId(docId);
            row.setLineNo(lineNo++);
            row.setSkuId(item.getSkuId());
            row.setSkuName(p.name());
            row.setUnit(p.unit());
            row.setSourceInboundItemId(item.getSourceInboundItemId());
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

    private PurInboundItem requireSourceLine(Long sourceItemId, Long inboundId, Long skuId) {
        PurInboundItem src = inboundItemMapper.selectById(sourceItemId);
        if (src == null || !src.getDocId().equals(inboundId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "原入库行不存在或不属于该入库单");
        }
        if (!src.getSkuId().equals(skuId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "退货行商品与原入库行不一致");
        }
        return src;
    }

    private PurInbound requireAuditedInbound(Long inboundId) {
        PurInbound source = inboundMapper.selectById(inboundId);
        if (source == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "原入库单不存在");
        }
        if (source.getStatus() != DocStatus.AUDITED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "原入库单须已审核（未作废）");
        }
        return source;
    }

    /**
     * 批次定位（M4 审查 I5）：有源行 lotId 已在保存时带出；无源管批次按 sku+批号 查既有批次，
     * 出库方向禁止 materialize 新批次。
     */
    private Long resolveLotId(PurReturnItem item, ProductReader.ProductInfo p) {
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
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "批次不存在: " + item.getLotNo() + "（退货不能新建批次）");
        }
        item.setLotId(lot.id());
        itemMapper.updateById(item);
        return lot.id();
    }

    private void requireWarehouseVisible(Long warehouseId) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
    }

    private static void requireIntegerQty(BigDecimal qty, String message) {
        if (qty == null || qty.stripTrailingZeros().scale() > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
    }

    private PurReturn requireDoc(Long id) {
        PurReturn doc = returnMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "退货单不存在");
        }
        return doc;
    }

    private List<PurReturnItem> itemsOf(Long docId) {
        return itemMapper.selectList(Wrappers.<PurReturnItem>lambdaQuery()
                .eq(PurReturnItem::getDocId, docId).orderByAsc(PurReturnItem::getLineNo));
    }

    public PageResult<PurReturn> page(int pageNum, int pageSize, String keyword) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && scope.isEmpty()) {
            PageResult<PurReturn> empty = new PageResult<>();
            empty.setTotal(0);
            empty.setRecords(List.of());
            return empty;
        }
        Page<PurReturn> page = returnMapper.selectPage(
                new Page<>(pageNum, pageSize),
                Wrappers.<PurReturn>lambdaQuery()
                        .in(scope != null, PurReturn::getWarehouseId, scope == null ? Set.of() : scope)
                        .like(keyword != null && !keyword.isBlank(), PurReturn::getDocNo, keyword)
                        .orderByDesc(PurReturn::getId));
        return PageResult.of(page);
    }

    public ReturnVO get(Long id) {
        PurReturn doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        ReturnVO vo = new ReturnVO();
        vo.setDoc(doc);
        vo.setItems(itemsOf(id));
        return vo;
    }
}
