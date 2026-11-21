package com.wille.erp.sale.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.sale.dto.QuoteItemRequest;
import com.wille.erp.sale.dto.QuoteSaveRequest;
import com.wille.erp.sale.dto.QuoteVO;
import com.wille.erp.sale.entity.SalOrder;
import com.wille.erp.sale.entity.SalOrderItem;
import com.wille.erp.sale.entity.SalQuote;
import com.wille.erp.sale.entity.SalQuoteItem;
import com.wille.erp.sale.mapper.SalOrderItemMapper;
import com.wille.erp.sale.mapper.SalOrderMapper;
import com.wille.erp.sale.mapper.SalQuoteItemMapper;
import com.wille.erp.sale.mapper.SalQuoteMapper;
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

/**
 * 销售报价单（spec §2.5/§8-1）：意向文档不锁库存不碰财务；转单生成一张销售订单草稿后头 COMPLETED。
 * 状态机纪律与询价一致（条件 UPDATE、草稿可作废、DRAFT/AUDITED 均可转单）。
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final SalQuoteMapper quoteMapper;
    private final SalQuoteItemMapper itemMapper;
    private final SalOrderMapper orderMapper;
    private final SalOrderItemMapper orderItemMapper;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final CurrentUserHolder currentUser;

    @Transactional(rollbackFor = Exception.class)
    public Long create(QuoteSaveRequest request) {
        SalQuote doc = new SalQuote();
        doc.setDocNo(docNoService.nextNo(DocType.SAL_QUOTE));
        doc.setStatus(DocStatus.DRAFT);
        doc.setDocDate(LocalDate.now());
        doc.setCurrency("CNY");
        doc.setCustomerId(request.getCustomerId());
        doc.setValidUntil(request.getValidUntil());
        doc.setFinFlag(0);
        quoteMapper.insert(doc);
        replaceItems(doc.getId(), request.getItems());
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, QuoteSaveRequest request) {
        SalQuote doc = requireDoc(id);
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
        doc.setCustomerId(request.getCustomerId());
        doc.setValidUntil(request.getValidUntil());
        quoteMapper.updateById(doc);
        replaceItems(id, request.getItems());
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        requireDoc(id);
        transit(id, DocStatus.DRAFT, DocStatus.AUDITED);
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidQuote(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因必填");
        }
        SalQuote doc = requireDoc(id);
        if (doc.getStatus() == DocStatus.COMPLETED || doc.getStatus() == DocStatus.VOIDED) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "已转单或已作废的报价单不可再作废");
        }
        int updated = quoteMapper.update(null, Wrappers.<SalQuote>lambdaUpdate()
                .eq(SalQuote::getId, id)
                .in(SalQuote::getStatus, DocStatus.DRAFT, DocStatus.AUDITED)
                .set(SalQuote::getStatus, DocStatus.VOIDED)
                .set(SalQuote::getVoidBy, currentUser.userId())
                .set(SalQuote::getVoidTime, LocalDateTime.now())
                .set(SalQuote::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
    }

    /** 整单转一张销售订单草稿（行 qty/price/amount 带出）；转后报价 COMPLETED 不可重复转 */
    @Transactional(rollbackFor = Exception.class)
    public Long convertToOrder(Long id, Long warehouseId) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
        SalQuote doc = requireDoc(id);
        if (doc.getStatus() != DocStatus.DRAFT && doc.getStatus() != DocStatus.AUDITED) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "该报价单已转单或已作废");
        }
        List<SalQuoteItem> items = itemsOf(id);
        SalOrder order = new SalOrder();
        order.setDocNo(docNoService.nextNo(DocType.SAL_ORDER));
        order.setStatus(DocStatus.DRAFT);
        order.setDocDate(LocalDate.now());
        order.setCurrency("CNY");
        order.setCustomerId(doc.getCustomerId());
        order.setWarehouseId(warehouseId);
        order.setFinFlag(0);
        orderMapper.insert(order);
        int lineNo = 1;
        for (SalQuoteItem item : items) {
            SalOrderItem line = new SalOrderItem();
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
        transit(id, doc.getStatus(), DocStatus.COMPLETED);
        return order.getId();
    }

    public PageResult<SalQuote> page(int pageNum, int pageSize, String keyword) {
        Page<SalQuote> page = quoteMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<SalQuote>lambdaQuery()
                        .like(keyword != null && !keyword.isBlank(), SalQuote::getDocNo, keyword)
                        .orderByDesc(SalQuote::getId));
        return PageResult.of(page);
    }

    public QuoteVO get(Long id) {
        QuoteVO vo = new QuoteVO();
        vo.setDoc(requireDoc(id));
        vo.setItems(itemsOf(id));
        return vo;
    }

    // ---------- 内部 ----------

    private void replaceItems(Long docId, List<QuoteItemRequest> items) {
        itemMapper.delete(Wrappers.<SalQuoteItem>lambdaQuery().eq(SalQuoteItem::getDocId, docId));
        int lineNo = 1;
        for (QuoteItemRequest item : items) {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            if (item.getQty() == null || item.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "报价数量必须大于 0");
            }
            SalQuoteItem row = new SalQuoteItem();
            row.setDocId(docId);
            row.setLineNo(lineNo++);
            row.setSkuId(item.getSkuId());
            row.setSkuName(p.name());
            row.setSpec(null);
            row.setUnit(p.unit());
            row.setQty(item.getQty());
            row.setPrice(item.getPrice());
            row.setAmount(amount(item.getQty(), item.getPrice()));
            row.setRemark(item.getRemark());
            itemMapper.insert(row);
        }
    }

    private static BigDecimal amount(BigDecimal qty, BigDecimal price) {
        return price == null ? null : qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
    }

    private List<SalQuoteItem> itemsOf(Long docId) {
        return itemMapper.selectList(Wrappers.<SalQuoteItem>lambdaQuery()
                .eq(SalQuoteItem::getDocId, docId).orderByAsc(SalQuoteItem::getLineNo));
    }

    private SalQuote requireDoc(Long id) {
        SalQuote doc = quoteMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报价单不存在");
        }
        return doc;
    }

    /** 条件状态 UPDATE 防并发（影响 0 行 → 10002） */
    private void transit(Long id, DocStatus from, DocStatus to) {
        int updated = quoteMapper.update(null, Wrappers.<SalQuote>lambdaUpdate()
                .eq(SalQuote::getId, id).eq(SalQuote::getStatus, from)
                .set(SalQuote::getStatus, to)
                .set(to == DocStatus.AUDITED, SalQuote::getAuditBy, currentUser.userId())
                .set(to == DocStatus.AUDITED, SalQuote::getAuditTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
    }
}
