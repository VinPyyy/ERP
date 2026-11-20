package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockCmd.ReverseCmd;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.inventory.dto.OpeningItemRequest;
import com.wille.erp.inventory.dto.OpeningSaveRequest;
import com.wille.erp.inventory.dto.OpeningVO;
import com.wille.erp.inventory.entity.InvFlow;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.entity.InvOpening;
import com.wille.erp.inventory.entity.InvOpeningItem;
import com.wille.erp.inventory.mapper.InvFlowMapper;
import com.wille.erp.inventory.mapper.InvLotMapper;
import com.wille.erp.inventory.mapper.InvOpeningItemMapper;
import com.wille.erp.inventory.mapper.InvOpeningMapper;
import com.wille.erp.system.api.CurrentUserHolder;
import com.wille.erp.system.api.DocNoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 期初库存单（spec §2.3/§6-M3）：草稿→审核（StockService.inbound OPENING）→可作废（快照冲正）。
 * 约束：一仓一期初单头；同仓库×SKU 仅一张非作废期初行；审核后该 wh×SKU 有后续流水禁作废。
 */
@Service
@RequiredArgsConstructor
public class OpeningService {

    private final InvOpeningMapper openingMapper;
    private final InvOpeningItemMapper itemMapper;
    private final InvLotMapper lotMapper;
    private final InvFlowMapper flowMapper;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final StockService stockService;
    private final CurrentUserHolder currentUser;

    @Transactional(rollbackFor = Exception.class)
    public Long create(OpeningSaveRequest request) {
        requireWarehouseVisible(request.getWarehouseId());
        validateItems(request.getItems());
        InvOpening doc = new InvOpening();
        doc.setDocNo(docNoService.nextNo(DocType.OPENING));
        doc.setStatus(DocStatus.DRAFT);
        doc.setDocDate(LocalDate.now());
        doc.setCurrency("CNY");
        doc.setWarehouseId(request.getWarehouseId());
        doc.setFinFlag(0);
        openingMapper.insert(doc);
        replaceItems(doc.getId(), request.getItems());
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, OpeningSaveRequest request) {
        InvOpening doc = requireOpening(id);
        requireDraft(doc);
        validateItems(request.getItems());
        replaceItems(id, request.getItems());
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        InvOpening doc = requireOpening(id);
        // 条件状态更新防双击（spec §4.1 步骤2）
        int updated = openingMapper.update(null, Wrappers.<InvOpening>lambdaUpdate()
                .eq(InvOpening::getId, id).eq(InvOpening::getStatus, DocStatus.DRAFT)
                .set(InvOpening::getStatus, DocStatus.AUDITED)
                .set(InvOpening::getAuditBy, currentUser.userId())
                .set(InvOpening::getAuditTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        List<InvOpeningItem> items = itemsOf(id);
        requireNoDuplicateOpening(doc, items);

        List<MoveLine> lines = items.stream().map(item -> new MoveLine(
                item.getSkuId(), item.getQty(), item.getCostPrice(),
                materializeLot(item), item.getSnList(), item.getId())).toList();
        stockService.inbound(new MoveCmd(doc.getWarehouseId(),
                new BizRef(BizType.OPENING, DocType.OPENING, doc.getId(), doc.getDocNo()), lines));
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidOpening(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因必填");
        }
        InvOpening doc = requireOpening(id);
        List<InvOpeningItem> items = itemsOf(id);
        requireNoSubsequentFlows(doc, items);

        int updated = openingMapper.update(null, Wrappers.<InvOpening>lambdaUpdate()
                .eq(InvOpening::getId, id).eq(InvOpening::getStatus, DocStatus.AUDITED)
                .set(InvOpening::getStatus, DocStatus.VOIDED)
                .set(InvOpening::getVoidBy, currentUser.userId())
                .set(InvOpening::getVoidTime, LocalDateTime.now())
                .set(InvOpening::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        stockService.reverse(new ReverseCmd(DocType.OPENING, id));
    }

    public OpeningVO get(Long id) {
        InvOpening doc = requireOpening(id);
        requireWarehouseVisible(doc.getWarehouseId());
        return toVO(doc, itemsOf(id));
    }

    public PageResult<OpeningVO> page(int pageNum, int pageSize) {
        Set<Long> scope = currentUser.warehouseScope();
        Page<InvOpening> page = openingMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<InvOpening>lambdaQuery()
                        .in(scope != null, InvOpening::getWarehouseId, scope == null ? Set.of() : scope)
                        .orderByDesc(InvOpening::getId));
        List<OpeningVO> records = page.getRecords().stream()
                .map(doc -> toVO(doc, List.of())).toList();
        PageResult<OpeningVO> result = new PageResult<>();
        result.setTotal(page.getTotal());
        result.setRecords(records);
        return result;
    }

    // ---------- 校验 ----------

    private void validateItems(List<OpeningItemRequest> items) {
        for (OpeningItemRequest item : items) {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            if (item.getQty() == null || item.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "期初数量必须大于 0");
            }
            if (item.getCostPrice() == null || item.getCostPrice().signum() < 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "期初均价不能为空且不能为负");
            }
            if (p.lotManaged() && (item.getLotNo() == null || item.getLotNo().isBlank())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管批次商品期初必须填批号");
            }
            if (p.snManaged()) {
                if (item.getSnList() == null || item.getSnList().size() != item.getQty().intValueExact()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "管SN商品期初SN数量必须与数量一致");
                }
            }
        }
        long distinct = items.stream().map(OpeningItemRequest::getSkuId).distinct().count();
        if (distinct != items.size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "同一期初单内 SKU 不能重复");
        }
    }

    /** 同仓库×SKU 仅一张非作废期初行（spec §2.3 应用层约束） */
    private void requireNoDuplicateOpening(InvOpening doc, List<InvOpeningItem> items) {
        List<Long> otherDocs = openingMapper.selectList(Wrappers.<InvOpening>lambdaQuery()
                        .eq(InvOpening::getWarehouseId, doc.getWarehouseId())
                        .ne(InvOpening::getStatus, DocStatus.VOIDED)
                        .ne(InvOpening::getId, doc.getId()))
                .stream().map(InvOpening::getId).toList();
        if (otherDocs.isEmpty()) {
            return;
        }
        Set<Long> skus = items.stream().map(InvOpeningItem::getSkuId).collect(Collectors.toSet());
        Long clash = itemMapper.selectCount(Wrappers.<InvOpeningItem>lambdaQuery()
                .in(InvOpeningItem::getDocId, otherDocs)
                .in(InvOpeningItem::getSkuId, skus));
        if (clash > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该仓库×SKU 已存在非作废期初记录");
        }
    }

    /** 审核后该 wh×SKU 已有非本单流水 → 禁作废（spec §4.2 步骤2） */
    private void requireNoSubsequentFlows(InvOpening doc, List<InvOpeningItem> items) {
        Set<Long> skus = items.stream().map(InvOpeningItem::getSkuId).collect(Collectors.toSet());
        Long count = flowMapper.selectCount(Wrappers.<InvFlow>lambdaQuery()
                .eq(InvFlow::getWarehouseId, doc.getWarehouseId())
                .in(InvFlow::getSkuId, skus)
                .and(w -> w.ne(InvFlow::getBizType, BizType.OPENING)
                        .or().ne(InvFlow::getBizDocId, doc.getId())));
        if (count > 0) {
            throw new BizException(ErrorCode.VOID_STOCK_CONSUMED,
                    "库存已被后续单据消耗，请先作废下游单据");
        }
    }

    // ---------- 落库辅助 ----------

    private void replaceItems(Long docId, List<OpeningItemRequest> items) {
        itemMapper.delete(Wrappers.<InvOpeningItem>lambdaQuery().eq(InvOpeningItem::getDocId, docId));
        int lineNo = 1;
        for (OpeningItemRequest item : items) {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            InvOpeningItem row = new InvOpeningItem();
            row.setDocId(docId);
            row.setLineNo(lineNo++);
            row.setSkuId(item.getSkuId());
            row.setSkuName(p.name());
            row.setSpec(null);
            row.setUnit(p.unit());
            row.setLotNo(item.getLotNo());
            row.setProductionDate(item.getProductionDate());
            row.setQty(item.getQty());
            row.setCostPrice(item.getCostPrice());
            row.setCostAmount(item.getQty().multiply(item.getCostPrice()).setScale(2, RoundingMode.HALF_UP));
            row.setSnList(item.getSnList());
            row.setRemark(item.getRemark());
            itemMapper.insert(row);
        }
    }

    /** 审核时物化批次主档：UNIQUE(sku,lot_no) 复用（spec §2.3） */
    private Long materializeLot(InvOpeningItem item) {
        if (item.getLotNo() == null || item.getLotNo().isBlank()) {
            return null;
        }
        InvLot lot = lotMapper.selectOne(Wrappers.<InvLot>lambdaQuery()
                .eq(InvLot::getSkuId, item.getSkuId()).eq(InvLot::getLotNo, item.getLotNo()));
        if (lot == null) {
            lot = new InvLot();
            lot.setSkuId(item.getSkuId());
            lot.setLotNo(item.getLotNo());
            lot.setProductionDate(item.getProductionDate());
            if (item.getProductionDate() != null) {
                ProductReader.ProductInfo p = productReader.read(item.getSkuId());
                if (p != null && p.shelfLifeDays() != null) {
                    lot.setExpiryDate(item.getProductionDate().plusDays(p.shelfLifeDays()));
                }
            }
            lotMapper.insert(lot);
        }
        return lot.getId();
    }

    private InvOpening requireOpening(Long id) {
        InvOpening doc = openingMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "期初单不存在");
        }
        return doc;
    }

    private void requireDraft(InvOpening doc) {
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
    }

    private void requireWarehouseVisible(Long warehouseId) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
    }

    private List<InvOpeningItem> itemsOf(Long docId) {
        return itemMapper.selectList(Wrappers.<InvOpeningItem>lambdaQuery()
                .eq(InvOpeningItem::getDocId, docId).orderByAsc(InvOpeningItem::getLineNo));
    }

    private OpeningVO toVO(InvOpening doc, List<InvOpeningItem> items) {
        OpeningVO vo = new OpeningVO();
        vo.setId(doc.getId());
        vo.setDocNo(doc.getDocNo());
        vo.setStatus(doc.getStatus() == null ? null : doc.getStatus().getCode());
        vo.setWarehouseId(doc.getWarehouseId());
        vo.setDocDate(doc.getDocDate());
        vo.setAuditTime(doc.getAuditTime());
        vo.setVoidReason(doc.getVoidReason());
        vo.setItems(items.stream().map(item -> {
            OpeningVO.OpeningItemVO iv = new OpeningVO.OpeningItemVO();
            iv.setId(item.getId());
            iv.setLineNo(item.getLineNo());
            iv.setSkuId(item.getSkuId());
            iv.setSkuName(item.getSkuName());
            iv.setQty(item.getQty());
            iv.setCostPrice(item.getCostPrice());
            iv.setCostAmount(item.getCostAmount());
            iv.setLotNo(item.getLotNo());
            iv.setProductionDate(item.getProductionDate());
            iv.setSnList(item.getSnList());
            iv.setRemark(item.getRemark());
            return iv;
        }).toList());
        return vo;
    }
}
