package com.wille.erp.purchase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.purchase.dto.InquiryItemRequest;
import com.wille.erp.purchase.dto.InquirySaveRequest;
import com.wille.erp.purchase.dto.InquiryVO;
import com.wille.erp.purchase.entity.PurInquiry;
import com.wille.erp.purchase.entity.PurInquiryItem;
import com.wille.erp.purchase.entity.PurOrder;
import com.wille.erp.purchase.entity.PurOrderItem;
import com.wille.erp.purchase.mapper.PurInquiryItemMapper;
import com.wille.erp.purchase.mapper.PurInquiryMapper;
import com.wille.erp.purchase.mapper.PurOrderItemMapper;
import com.wille.erp.purchase.mapper.PurOrderMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 询价单（spec §2.4）：意向文档，不碰库存财务；选中行按供应商分组转采购订单草稿。
 * 状态机：10 草稿 → 20 已审核 → 30 已转单（COMPLETED）；任意非终态可作废 50。
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

    private final PurInquiryMapper inquiryMapper;
    private final PurInquiryItemMapper itemMapper;
    private final PurOrderMapper orderMapper;
    private final PurOrderItemMapper orderItemMapper;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final CurrentUserHolder currentUser;

    @Transactional(rollbackFor = Exception.class)
    public Long create(InquirySaveRequest request) {
        PurInquiry doc = new PurInquiry();
        doc.setDocNo(docNoService.nextNo(DocType.PUR_INQUIRY));
        doc.setStatus(DocStatus.DRAFT);
        doc.setDocDate(LocalDate.now());
        doc.setCurrency("CNY");
        doc.setTitle(request.getTitle());
        doc.setFinFlag(0);
        inquiryMapper.insert(doc);
        replaceItems(doc.getId(), request.getItems());
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, InquirySaveRequest request) {
        PurInquiry doc = requireDoc(id);
        requireStatus(doc, DocStatus.DRAFT, "仅草稿可编辑");
        replaceItems(id, request.getItems());
        if (request.getTitle() != null) {
            doc.setTitle(request.getTitle());
            inquiryMapper.updateById(doc);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        requireDoc(id);
        transit(id, DocStatus.DRAFT, DocStatus.AUDITED);
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidInquiry(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因必填");
        }
        PurInquiry doc = requireDoc(id);
        if (doc.getStatus() == DocStatus.COMPLETED || doc.getStatus() == DocStatus.VOIDED) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "已转单或已作废的询价单不可再作废");
        }
        transitToVoid(id, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void selectLine(Long itemId, boolean selected) {
        PurInquiryItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "询价行不存在");
        }
        PurInquiry doc = requireDoc(item.getDocId());
        if (doc.getStatus() == DocStatus.COMPLETED || doc.getStatus() == DocStatus.VOIDED) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "单据已终态，不可改选中");
        }
        item.setSelected(selected ? 1 : 0);
        itemMapper.updateById(item);
    }

    /** 选中行按供应商分组转采购订单草稿；转单后询价单置 COMPLETED（一次性，不可重复转） */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> convertToOrders(Long id, Long warehouseId) {
        PurInquiry doc = requireDoc(id);
        if (doc.getStatus() != DocStatus.DRAFT && doc.getStatus() != DocStatus.AUDITED) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿/已审核询价单可转订单");
        }
        List<PurInquiryItem> selected = itemMapper.selectList(Wrappers.<PurInquiryItem>lambdaQuery()
                .eq(PurInquiryItem::getDocId, id).eq(PurInquiryItem::getSelected, 1));
        if (selected.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "没有选中的比价行");
        }
        Map<Long, List<PurInquiryItem>> bySupplier = new LinkedHashMap<>();
        for (PurInquiryItem item : selected) {
            bySupplier.computeIfAbsent(item.getSupplierId(), k -> new ArrayList<>()).add(item);
        }
        List<Long> orderIds = new ArrayList<>();
        for (Map.Entry<Long, List<PurInquiryItem>> group : bySupplier.entrySet()) {
            PurOrder order = new PurOrder();
            order.setDocNo(docNoService.nextNo(DocType.PUR_ORDER));
            order.setStatus(DocStatus.DRAFT);
            order.setDocDate(LocalDate.now());
            order.setCurrency("CNY");
            order.setSupplierId(group.getKey());
            order.setWarehouseId(warehouseId);
            order.setFinFlag(0);
            orderMapper.insert(order);
            int lineNo = 1;
            for (PurInquiryItem item : group.getValue()) {
                PurOrderItem line = new PurOrderItem();
                line.setDocId(order.getId());
                line.setLineNo(lineNo++);
                line.setSkuId(item.getSkuId());
                line.setSkuName(item.getSkuName());
                line.setSpec(item.getSpec());
                line.setUnit(item.getUnit());
                line.setQty(item.getQty());
                line.setPrice(item.getPrice());
                line.setAmount(amount(item.getQty(), item.getPrice()));
                line.setDoneQty(BigDecimal.ZERO);
                line.setLineStatus(10);
                orderItemMapper.insert(line);
            }
            orderIds.add(order.getId());
        }
        transit(id, doc.getStatus(), DocStatus.COMPLETED);
        return orderIds;
    }

    public InquiryVO get(Long id) {
        PurInquiry doc = requireDoc(id);
        InquiryVO vo = new InquiryVO();
        vo.setId(doc.getId());
        vo.setDocNo(doc.getDocNo());
        vo.setStatus(doc.getStatus().getCode());
        vo.setTitle(doc.getTitle());
        vo.setDocDate(doc.getDocDate());
        vo.setAuditTime(doc.getAuditTime());
        vo.setVoidReason(doc.getVoidReason());
        vo.setItems(itemMapper.selectList(Wrappers.<PurInquiryItem>lambdaQuery()
                        .eq(PurInquiryItem::getDocId, id).orderByAsc(PurInquiryItem::getLineNo)).stream()
                .map(item -> {
                    InquiryVO.InquiryItemVO iv = new InquiryVO.InquiryItemVO();
                    iv.setId(item.getId());
                    iv.setLineNo(item.getLineNo());
                    iv.setSkuId(item.getSkuId());
                    iv.setSkuName(item.getSkuName());
                    iv.setUnit(item.getUnit());
                    iv.setSupplierId(item.getSupplierId());
                    iv.setQty(item.getQty());
                    iv.setPrice(item.getPrice());
                    iv.setAmount(item.getAmount());
                    iv.setLeadTimeDays(item.getLeadTimeDays());
                    iv.setSelected(item.getSelected());
                    return iv;
                }).toList());
        return vo;
    }

    public PageResult<InquiryVO> page(int pageNum, int pageSize, String keyword) {
        Page<PurInquiry> page = inquiryMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<PurInquiry>lambdaQuery()
                        .like(keyword != null && !keyword.isBlank(), PurInquiry::getDocNo, keyword)
                        .orderByDesc(PurInquiry::getId));
        return new PageResult<>(page.getTotal(), page.getRecords().stream().map(doc -> {
            InquiryVO vo = new InquiryVO();
            vo.setId(doc.getId());
            vo.setDocNo(doc.getDocNo());
            vo.setStatus(doc.getStatus().getCode());
            vo.setTitle(doc.getTitle());
            vo.setDocDate(doc.getDocDate());
            return vo;
        }).toList());
    }

    // ---------- 内部 ----------

    private void replaceItems(Long docId, List<InquiryItemRequest> items) {
        itemMapper.delete(Wrappers.<PurInquiryItem>lambdaQuery().eq(PurInquiryItem::getDocId, docId));
        int lineNo = 1;
        for (InquiryItemRequest item : items) {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            if (item.getSupplierId() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "询价行必须指定供应商");
            }
            if (item.getQty() == null || item.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "询价数量必须大于 0");
            }
            PurInquiryItem row = new PurInquiryItem();
            row.setDocId(docId);
            row.setLineNo(lineNo++);
            row.setSkuId(item.getSkuId());
            row.setSkuName(p.name());
            row.setSpec(null);
            row.setUnit(p.unit());
            row.setSupplierId(item.getSupplierId());
            row.setQty(item.getQty());
            row.setPrice(item.getPrice());
            row.setAmount(amount(item.getQty(), item.getPrice()));
            row.setLeadTimeDays(item.getLeadTimeDays());
            row.setSelected(0);
            row.setRemark(item.getRemark());
            itemMapper.insert(row);
        }
    }

    private static BigDecimal amount(BigDecimal qty, BigDecimal price) {
        return price == null ? null : qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
    }

    private PurInquiry requireDoc(Long id) {
        PurInquiry doc = inquiryMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "询价单不存在");
        }
        return doc;
    }

    private void requireStatus(PurInquiry doc, DocStatus expected, String message) {
        if (doc.getStatus() != expected) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, message);
        }
    }

    /** 条件状态 UPDATE 防并发（影响 0 行 → 10002） */
    private void transit(Long id, DocStatus from, DocStatus to) {
        int updated = inquiryMapper.update(null, Wrappers.<PurInquiry>lambdaUpdate()
                .eq(PurInquiry::getId, id).eq(PurInquiry::getStatus, from)
                .set(PurInquiry::getStatus, to)
                .set(to == DocStatus.AUDITED, PurInquiry::getAuditBy, currentUser.userId())
                .set(to == DocStatus.AUDITED, PurInquiry::getAuditTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
    }

    private void transitToVoid(Long id, String reason) {
        int updated = inquiryMapper.update(null, Wrappers.<PurInquiry>lambdaUpdate()
                .eq(PurInquiry::getId, id)
                .in(PurInquiry::getStatus, DocStatus.DRAFT, DocStatus.AUDITED)
                .set(PurInquiry::getStatus, DocStatus.VOIDED)
                .set(PurInquiry::getVoidBy, currentUser.userId())
                .set(PurInquiry::getVoidTime, LocalDateTime.now())
                .set(PurInquiry::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
    }
}
