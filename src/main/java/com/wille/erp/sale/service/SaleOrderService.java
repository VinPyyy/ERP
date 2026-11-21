package com.wille.erp.sale.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.event.DocEvent;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.CountLockGuard;
import com.wille.erp.inventory.api.StockCmd.LockCmd;
import com.wille.erp.inventory.api.StockCmd.LockLine;
import com.wille.erp.inventory.api.StockCmd.LockRef;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.sale.dto.OutboundItemRequest;
import com.wille.erp.sale.dto.OutboundSaveRequest;
import com.wille.erp.sale.dto.SaleOrderItemRequest;
import com.wille.erp.sale.dto.SaleOrderSaveRequest;
import com.wille.erp.sale.dto.SaleOrderVO;
import com.wille.erp.sale.entity.SalOrder;
import com.wille.erp.sale.entity.SalOrderItem;
import com.wille.erp.sale.entity.SalOutbound;
import com.wille.erp.sale.event.SaleOrderAuditedEvent;
import com.wille.erp.sale.mapper.SalOrderItemMapper;
import com.wille.erp.sale.mapper.SalOrderMapper;
import com.wille.erp.sale.mapper.SalOutboundMapper;
import com.wille.erp.system.api.ConfigService;
import com.wille.erp.system.api.CurrentUserHolder;
import com.wille.erp.system.api.DocNoService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 销售订单（spec §2.5/§3e/§4.1 前置）：审核即按仓库×SKU 锁定库存（整单成败，§3e）；
 * 行关闭释放剩余锁定；作废前提无未作废下游出库单、全额释放；盘点锁接入审核（§3d）。
 * 与采购订单的对称差异：采购订单不锁、销售订单锁。
 */
@Service
@RequiredArgsConstructor
public class SaleOrderService {

    private final SalOrderMapper orderMapper;
    private final SalOrderItemMapper orderItemMapper;
    private final SalOutboundMapper outboundMapper;
    private final SaleOutboundService outboundService;
    private final StockService stockService;
    private final CountLockGuard countLockGuard;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final ConfigService configService;
    private final CurrentUserHolder currentUser;
    private final ApplicationEventPublisher events;

    @Transactional(rollbackFor = Exception.class)
    public Long create(SaleOrderSaveRequest request) {
        requireWarehouseVisible(request.getWarehouseId());
        SalOrder doc = new SalOrder();
        doc.setDocNo(docNoService.nextNo(DocType.SAL_ORDER));
        doc.setStatus(DocStatus.DRAFT);
        doc.setDocDate(LocalDate.now());
        doc.setCurrency("CNY");
        doc.setCustomerId(request.getCustomerId());
        doc.setWarehouseId(request.getWarehouseId());
        doc.setExpectDate(request.getExpectDate());
        doc.setFinFlag(0);
        orderMapper.insert(doc);
        replaceItems(doc.getId(), request.getItems());
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SaleOrderSaveRequest request) {
        SalOrder doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
        replaceItems(id, request.getItems());
    }

    /**
     * 审核即锁定（spec §4.1 前置语义）：条件 UPDATE 防双击 → 盘点锁 → 可用量预检（整单视角，
     * 报错友好且测试语义零残留）→ 逐行门面锁定（FOR UPDATE 权威防线）→ 事件。
     */
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        SalOrder doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        int updated = orderMapper.update(null, Wrappers.<SalOrder>lambdaUpdate()
                .eq(SalOrder::getId, id).eq(SalOrder::getStatus, DocStatus.DRAFT)
                .set(SalOrder::getStatus, DocStatus.AUDITED)
                .set(SalOrder::getAuditBy, currentUser.userId())
                .set(SalOrder::getAuditTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        List<SalOrderItem> items = itemsOf(id);
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单无明细行");
        }
        if (countLockGuard.counting(doc.getWarehouseId(),
                items.stream().map(SalOrderItem::getSkuId).toList())) {
            throw new BizException(ErrorCode.COUNT_LOCK_CONFLICT, "盘点中，禁止订单审核锁定");
        }
        precheckAvailable(doc.getWarehouseId(), items);
        for (SalOrderItem item : items) {
            stockService.lock(new LockCmd(doc.getWarehouseId(),
                    new LockRef(DocType.SAL_ORDER, doc.getId(), item.getId()),
                    List.of(new LockLine(item.getSkuId(), item.getQty()))));
        }
        events.publishEvent(new SaleOrderAuditedEvent(doc.getId(), doc.getDocNo(), DocEvent.Action.AUDITED));
    }

    /** 行手动关闭（spec §3e-3）：10→40，释放该行剩余锁定；全行终态→头 COMPLETED */
    @Transactional(rollbackFor = Exception.class)
    public void closeItem(Long orderId, Long itemId) {
        SalOrder doc = requireDoc(orderId);
        requireWarehouseVisible(doc.getWarehouseId());
        if (doc.getStatus() != DocStatus.AUDITED) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅已审核订单可关行");
        }
        SalOrderItem item = orderItemMapper.selectById(itemId);
        if (item == null || !item.getDocId().equals(orderId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单行不存在");
        }
        int rows = orderItemMapper.update(null, Wrappers.<SalOrderItem>lambdaUpdate()
                .eq(SalOrderItem::getId, itemId).eq(SalOrderItem::getLineStatus, 10)
                .set(SalOrderItem::getLineStatus, 40));
        if (rows == 0) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "该行已终态");
        }
        stockService.unlock(new LockRef(DocType.SAL_ORDER, orderId, itemId));
        completeIfAllTerminal(orderId);
    }

    /** 作废：无未作废下游出库单（逆序 §4.2）；已审核单全额释放锁定 */
    @Transactional(rollbackFor = Exception.class)
    public void voidOrder(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因必填");
        }
        SalOrder doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        Long downstream = outboundMapper.selectCount(Wrappers.<SalOutbound>lambdaQuery()
                .eq(SalOutbound::getSourceOrderId, id)
                .ne(SalOutbound::getStatus, DocStatus.VOIDED));
        if (downstream > 0) {
            throw new BizException(ErrorCode.VOID_BLOCKED_BY_DOWNSTREAM,
                    "存在未作废的下游出库单，请先作废出库单");
        }
        int updated = orderMapper.update(null, Wrappers.<SalOrder>lambdaUpdate()
                .eq(SalOrder::getId, id)
                .in(SalOrder::getStatus, DocStatus.DRAFT, DocStatus.AUDITED)
                .set(SalOrder::getStatus, DocStatus.VOIDED)
                .set(SalOrder::getVoidBy, currentUser.userId())
                .set(SalOrder::getVoidTime, LocalDateTime.now())
                .set(SalOrder::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        if (doc.getStatus() == DocStatus.AUDITED) {
            for (SalOrderItem item : itemsOf(id)) {
                stockService.unlock(new LockRef(DocType.SAL_ORDER, id, item.getId()));
            }
            events.publishEvent(new SaleOrderAuditedEvent(doc.getId(), doc.getDocNo(), DocEvent.Action.VOIDED));
        }
    }

    /** 带出未履约行（qty−done，行价带出）生成出库单草稿（spec §5 create-outbound） */
    @Transactional(rollbackFor = Exception.class)
    public Long createOutbound(Long orderId) {
        SalOrder doc = requireDoc(orderId);
        requireWarehouseVisible(doc.getWarehouseId());
        if (doc.getStatus() != DocStatus.AUDITED) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅已审核订单可生成出库单");
        }
        List<SalOrderItem> open = orderItemMapper.selectList(Wrappers.<SalOrderItem>lambdaQuery()
                .eq(SalOrderItem::getDocId, orderId)
                .eq(SalOrderItem::getLineStatus, 10)
                .apply("done_qty < qty"));
        if (open.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单已全部履约");
        }
        OutboundSaveRequest request = new OutboundSaveRequest();
        request.setCustomerId(doc.getCustomerId());
        request.setWarehouseId(doc.getWarehouseId());
        request.setSourceOrderId(orderId);
        List<OutboundItemRequest> lines = new ArrayList<>();
        for (SalOrderItem item : open) {
            OutboundItemRequest line = new OutboundItemRequest();
            line.setSourceOrderItemId(item.getId());
            line.setSkuId(item.getSkuId());
            line.setQty(item.getQty().subtract(item.getDoneQty()));
            line.setPrice(item.getPrice());
            lines.add(line);
        }
        request.setItems(lines);
        return outboundService.create(request);
    }

    public PageResult<SalOrder> page(int pageNum, int pageSize, String keyword) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && scope.isEmpty()) {
            PageResult<SalOrder> empty = new PageResult<>();
            empty.setTotal(0);
            empty.setRecords(List.of());
            return empty;
        }
        Page<SalOrder> page = orderMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<SalOrder>lambdaQuery()
                        .in(scope != null, SalOrder::getWarehouseId, scope == null ? Set.of() : scope)
                        .like(keyword != null && !keyword.isBlank(), SalOrder::getDocNo, keyword)
                        .orderByDesc(SalOrder::getId));
        return PageResult.of(page);
    }

    public SaleOrderVO get(Long id) {
        SalOrder doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        SaleOrderVO vo = new SaleOrderVO();
        vo.setDoc(doc);
        vo.setItems(itemsOf(id));
        return vo;
    }

    // ---------- 内部 ----------

    /** 整单可用量预检（allow-negative 开则跳过）：报错友好 + 失败先于任何写锁 */
    private void precheckAvailable(Long warehouseId, List<SalOrderItem> items) {
        if (configService.getBool(ConfigService.KEY_ALLOW_NEGATIVE)) {
            return;
        }
        Map<Long, BigDecimal> need = new HashMap<>();
        for (SalOrderItem item : items) {
            need.merge(item.getSkuId(), item.getQty(), BigDecimal::add);
        }
        for (Map.Entry<Long, BigDecimal> entry : need.entrySet()) {
            BigDecimal available = stockService.available(warehouseId, entry.getKey());
            if (available.compareTo(entry.getValue()) < 0) {
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                        "可用量不足: 需要 " + entry.getValue().stripTrailingZeros().toPlainString()
                                + " 可用 " + available.stripTrailingZeros().toPlainString());
            }
        }
    }

    /** 全行终态（30/40）→ 头 AUDITED→COMPLETED（与采购 completeIfAllTerminal 同语义） */
    void completeIfAllTerminal(Long orderId) {
        Long open = orderItemMapper.selectCount(Wrappers.<SalOrderItem>lambdaQuery()
                .eq(SalOrderItem::getDocId, orderId)
                .eq(SalOrderItem::getLineStatus, 10));
        if (open == 0) {
            orderMapper.update(null, Wrappers.<SalOrder>lambdaUpdate()
                    .eq(SalOrder::getId, orderId).eq(SalOrder::getStatus, DocStatus.AUDITED)
                    .set(SalOrder::getStatus, DocStatus.COMPLETED));
        }
    }

    private void replaceItems(Long docId, List<SaleOrderItemRequest> items) {
        orderItemMapper.delete(Wrappers.<SalOrderItem>lambdaQuery().eq(SalOrderItem::getDocId, docId));
        int lineNo = 1;
        for (SaleOrderItemRequest item : items) {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            if (item.getQty() == null || item.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "数量必须大于 0");
            }
            SalOrderItem row = new SalOrderItem();
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

    private List<SalOrderItem> itemsOf(Long docId) {
        return orderItemMapper.selectList(Wrappers.<SalOrderItem>lambdaQuery()
                .eq(SalOrderItem::getDocId, docId).orderByAsc(SalOrderItem::getLineNo));
    }

    SalOrder requireDoc(Long id) {
        SalOrder doc = orderMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "销售订单不存在");
        }
        return doc;
    }

    private void requireWarehouseVisible(Long warehouseId) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
    }
}
