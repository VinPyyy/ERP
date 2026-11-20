package com.wille.erp.purchase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.CountLockGuard;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.purchase.dto.ReturnItemRequest;
import com.wille.erp.purchase.dto.ReturnSaveRequest;
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
import java.util.Map;

/**
 * 采购退货（出库方向，spec §2.4/§8-6）：有原单→价强制原单价、累计退货 ≤ 原入库量（40002）；
 * 无原单→价手录；成本一律当前均价（门面结转）；SN 经门面核销出库。
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
    private final CurrentUserHolder currentUser;

    @Transactional(rollbackFor = Exception.class)
    public Long create(ReturnSaveRequest request) {
        PurInbound source = null;
        if (request.getSourceInboundId() != null) {
            source = inboundMapper.selectById(request.getSourceInboundId());
            if (source == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "原入库单不存在");
            }
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
        replaceItems(doc.getId(), request.getItems(), source != null);
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ReturnSaveRequest request) {
        PurReturn doc = requireDoc(id);
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
        replaceItems(id, request.getItems(), doc.getSourceInboundId() != null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        PurReturn doc = requireDoc(id);
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
            if (p.lotManaged() && (item.getLotNo() == null || item.getLotNo().isBlank())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管批次商品必须填批号");
            }
            if (p.snManaged() && (item.getSnList() == null
                    || item.getSnList().size() != item.getQty().intValueExact())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管SN商品SN数量必须与退货量一致");
            }
            Long lotId = (item.getLotNo() == null || item.getLotNo().isBlank())
                    ? null : item.getLotId();
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
        requireDoc(id);
        int updated = returnMapper.update(null, Wrappers.<PurReturn>lambdaUpdate()
                .eq(PurReturn::getId, id).eq(PurReturn::getStatus, DocStatus.AUDITED)
                .set(PurReturn::getStatus, DocStatus.VOIDED)
                .set(PurReturn::getVoidBy, currentUser.userId())
                .set(PurReturn::getVoidTime, LocalDateTime.now())
                .set(PurReturn::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        stockService.reverse(new com.wille.erp.inventory.api.StockCmd.ReverseCmd(DocType.PUR_RETURN, id));
    }

    /** 有原单：累计退货（含本单、不含已作废单）≤ 原入库量（spec §2.4） */
    private void checkReturnQuantities(List<PurReturnItem> items) {
        Map<Long, BigDecimal> bySourceLine = new HashMap<>();
        for (PurReturnItem item : items) {
            if (item.getSourceInboundItemId() != null) {
                bySourceLine.merge(item.getSourceInboundItemId(), item.getQty(), BigDecimal::add);
            }
        }
        for (Map.Entry<Long, BigDecimal> entry : bySourceLine.entrySet()) {
            List<Long> activeReturns = returnMapper.selectList(Wrappers.<PurReturn>lambdaQuery()
                            .ne(PurReturn::getStatus, DocStatus.VOIDED))
                    .stream().map(PurReturn::getId).toList();
            BigDecimal already = activeReturns.isEmpty() ? BigDecimal.ZERO
                    : itemMapper.selectList(Wrappers.<PurReturnItem>lambdaQuery()
                            .in(PurReturnItem::getDocId, activeReturns)
                            .eq(PurReturnItem::getSourceInboundItemId, entry.getKey()))
                    .stream().map(PurReturnItem::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            PurInboundItem source = inboundItemMapper.selectById(entry.getKey());
            if (source == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "原入库行不存在");
            }
            if (already.compareTo(source.getQty()) > 0) {
                throw new BizException(ErrorCode.RETURN_EXCEED_SOURCE,
                        "累计退货量超过原入库量: " + already.stripTrailingZeros().toPlainString()
                                + " > " + source.getQty().stripTrailingZeros().toPlainString());
            }
        }
    }

    private void replaceItems(Long docId, List<ReturnItemRequest> items, boolean hasSource) {
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
            if (item.getSourceInboundItemId() != null) {
                PurInboundItem source = inboundItemMapper.selectById(item.getSourceInboundItemId());
                if (source == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "原入库行不存在");
                }
                // 有原单：价强制原单价，不可改（spec §2.4）
                price = source.getPrice();
                lotId = source.getLotId();
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
            row.setLotNo(item.getLotNo());
            row.setSnList(item.getSnList());
            row.setReason(item.getReason());
            itemMapper.insert(row);
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
}
