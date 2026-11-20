package com.wille.erp.purchase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.purchase.dto.InboundItemRequest;
import com.wille.erp.purchase.dto.InboundSaveRequest;
import com.wille.erp.purchase.dto.OrderItemRequest;
import com.wille.erp.purchase.dto.OrderSaveRequest;
import com.wille.erp.purchase.entity.PurInbound;
import com.wille.erp.purchase.entity.PurOrder;
import com.wille.erp.purchase.entity.PurOrderItem;
import com.wille.erp.purchase.mapper.PurInboundMapper;
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
import java.util.List;

/**
 * 采购订单（spec §2.4/§3e-3）：审核不锁库存（采购订单无锁定语义）；
 * 行关闭/自动完成/全行终态→头 COMPLETED；作废须无未作废下游入库单（逆序作废）。
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final PurOrderMapper orderMapper;
    private final PurOrderItemMapper orderItemMapper;
    private final PurInboundMapper inboundMapper;
    private final InboundService inboundService;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final CurrentUserHolder currentUser;

    @Transactional(rollbackFor = Exception.class)
    public Long create(OrderSaveRequest request) {
        PurOrder doc = new PurOrder();
        doc.setDocNo(docNoService.nextNo(DocType.PUR_ORDER));
        doc.setStatus(DocStatus.DRAFT);
        doc.setDocDate(LocalDate.now());
        doc.setCurrency("CNY");
        doc.setSupplierId(request.getSupplierId());
        doc.setWarehouseId(request.getWarehouseId());
        doc.setExpectDate(request.getExpectDate());
        doc.setFinFlag(0);
        orderMapper.insert(doc);
        replaceItems(doc.getId(), request.getItems());
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, OrderSaveRequest request) {
        PurOrder doc = requireDoc(id);
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
        replaceItems(id, request.getItems());
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        requireDoc(id);
        int updated = orderMapper.update(null, Wrappers.<PurOrder>lambdaUpdate()
                .eq(PurOrder::getId, id).eq(PurOrder::getStatus, DocStatus.DRAFT)
                .set(PurOrder::getStatus, DocStatus.AUDITED)
                .set(PurOrder::getAuditBy, currentUser.userId())
                .set(PurOrder::getAuditTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
    }

    /** 逆序作废：存在未作废下游入库单即拒（spec §4.2 步骤2） */
    @Transactional(rollbackFor = Exception.class)
    public void voidOrder(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因必填");
        }
        requireDoc(id);
        Long downstream = inboundMapper.selectCount(Wrappers.<PurInbound>lambdaQuery()
                .eq(PurInbound::getSourceOrderId, id)
                .ne(PurInbound::getStatus, DocStatus.VOIDED));
        if (downstream > 0) {
            throw new BizException(ErrorCode.VOID_BLOCKED_BY_DOWNSTREAM,
                    "存在未作废的下游入库单，请先作废入库单");
        }
        int updated = orderMapper.update(null, Wrappers.<PurOrder>lambdaUpdate()
                .eq(PurOrder::getId, id)
                .in(PurOrder::getStatus, DocStatus.DRAFT, DocStatus.AUDITED)
                .set(PurOrder::getStatus, DocStatus.VOIDED)
                .set(PurOrder::getVoidBy, currentUser.userId())
                .set(PurOrder::getVoidTime, LocalDateTime.now())
                .set(PurOrder::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
    }

    /** 行手动关闭（spec §3e-3）：10→40；全行终态→头 COMPLETED */
    @Transactional(rollbackFor = Exception.class)
    public void closeItem(Long orderId, Long itemId) {
        PurOrder doc = requireDoc(orderId);
        if (doc.getStatus() != DocStatus.AUDITED) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅已审核订单可关行");
        }
        PurOrderItem item = orderItemMapper.selectById(itemId);
        if (item == null || !item.getDocId().equals(orderId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单行不存在");
        }
        if (item.getLineStatus() != 10) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "该行已终态");
        }
        item.setLineStatus(40);
        orderItemMapper.updateById(item);
        completeIfAllTerminal(orderId);
    }

    /** 带出未履约行生成入库单草稿（spec §5 create-inbound） */
    @Transactional(rollbackFor = Exception.class)
    public Long createInbound(Long orderId) {
        PurOrder doc = requireDoc(orderId);
        if (doc.getStatus() != DocStatus.AUDITED) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅已审核订单可生成入库单");
        }
        List<PurOrderItem> open = orderItemMapper.selectList(Wrappers.<PurOrderItem>lambdaQuery()
                .eq(PurOrderItem::getDocId, orderId)
                .eq(PurOrderItem::getLineStatus, 10)
                .apply("done_qty < qty"));
        if (open.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单已全部履约");
        }
        InboundSaveRequest request = new InboundSaveRequest();
        request.setSupplierId(doc.getSupplierId());
        request.setWarehouseId(doc.getWarehouseId());
        request.setSourceOrderId(orderId);
        List<InboundItemRequest> lines = new ArrayList<>();
        for (PurOrderItem item : open) {
            InboundItemRequest line = new InboundItemRequest();
            line.setSourceOrderItemId(item.getId());
            line.setSkuId(item.getSkuId());
            line.setQty(item.getQty().subtract(item.getDoneQty()));
            line.setPrice(item.getPrice());
            lines.add(line);
        }
        request.setItems(lines);
        return inboundService.create(request);
    }

    void completeIfAllTerminal(Long orderId) {
        Long open = orderItemMapper.selectCount(Wrappers.<PurOrderItem>lambdaQuery()
                .eq(PurOrderItem::getDocId, orderId)
                .eq(PurOrderItem::getLineStatus, 10));
        if (open == 0) {
            orderMapper.update(null, Wrappers.<PurOrder>lambdaUpdate()
                    .eq(PurOrder::getId, orderId).eq(PurOrder::getStatus, DocStatus.AUDITED)
                    .set(PurOrder::getStatus, DocStatus.COMPLETED));
        }
    }

    private void replaceItems(Long docId, List<OrderItemRequest> items) {
        orderItemMapper.delete(Wrappers.<PurOrderItem>lambdaQuery().eq(PurOrderItem::getDocId, docId));
        int lineNo = 1;
        for (OrderItemRequest item : items) {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            if (item.getQty() == null || item.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "数量必须大于 0");
            }
            PurOrderItem row = new PurOrderItem();
            row.setDocId(docId);
            row.setLineNo(lineNo++);
            row.setSkuId(item.getSkuId());
            row.setSkuName(p.name());
            row.setUnit(p.unit());
            row.setQty(item.getQty());
            row.setPrice(item.getPrice());
            row.setAmount(item.getPrice() == null ? null
                    : item.getQty().multiply(item.getPrice()).setScale(2, RoundingMode.HALF_UP));
            row.setDoneQty(BigDecimal.ZERO);
            row.setLineStatus(10);
            row.setRemark(item.getRemark());
            orderItemMapper.insert(row);
        }
    }

    PurOrder requireDoc(Long id) {
        PurOrder doc = orderMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "采购订单不存在");
        }
        return doc;
    }
}
