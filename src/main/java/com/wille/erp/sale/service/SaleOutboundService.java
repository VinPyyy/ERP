package com.wille.erp.sale.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.event.DocEvent;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.api.CountLockGuard;
import com.wille.erp.inventory.api.LotApi;
import com.wille.erp.inventory.api.StockCmd.BizRef;
import com.wille.erp.inventory.api.StockCmd.LockCmd;
import com.wille.erp.inventory.api.StockCmd.LockLine;
import com.wille.erp.inventory.api.StockCmd.LockRef;
import com.wille.erp.inventory.api.StockCmd.MoveCmd;
import com.wille.erp.inventory.api.StockCmd.MoveLine;
import com.wille.erp.inventory.api.StockCmd.ReverseCmd;
import com.wille.erp.inventory.api.StockService;
import com.wille.erp.sale.dto.OutboundItemRequest;
import com.wille.erp.sale.dto.SaleDocOptionVO;
import com.wille.erp.sale.dto.OutboundSaveRequest;
import com.wille.erp.sale.dto.OutboundVO;
import com.wille.erp.sale.entity.SalOrder;
import com.wille.erp.sale.entity.SalOrderItem;
import com.wille.erp.sale.entity.SalOutbound;
import com.wille.erp.sale.entity.SalOutboundItem;
import com.wille.erp.sale.entity.SalReturn;
import com.wille.erp.sale.event.SaleOutboundAuditedEvent;
import com.wille.erp.sale.mapper.SalOrderItemMapper;
import com.wille.erp.sale.mapper.SalOrderMapper;
import com.wille.erp.sale.mapper.SalOutboundItemMapper;
import com.wille.erp.sale.mapper.SalOutboundMapper;
import com.wille.erp.sale.mapper.SalReturnMapper;
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
 * 销售出库单（spec §4.1/§8-5，出库即发货）：审核链=条件状态→源绑定与超发预检→盘点锁→
 * 形态/批次校验→锁定核销（先释放以让出可用量）→门面实扣→done 原子累加（并发权威防线，
 * 0 行→50001 整单回滚）→完成联动→事件。作废=下游退货校验→reverse→按原 ref 重锁→
 * done 回减→头回退（仅存在被重开行时，I2 语义）。
 */
@Service
@RequiredArgsConstructor
public class SaleOutboundService {

    private final SalOutboundMapper outboundMapper;
    private final SalOutboundItemMapper itemMapper;
    private final SalOrderMapper orderMapper;
    private final SalOrderItemMapper orderItemMapper;
    private final SalReturnMapper returnMapper;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final StockService stockService;
    private final CountLockGuard countLockGuard;
    private final LotApi lotApi;
    private final CurrentUserHolder currentUser;
    private final ApplicationEventPublisher events;

    @Transactional(rollbackFor = Exception.class)
    public Long create(OutboundSaveRequest request) {
        requireWarehouseVisible(request.getWarehouseId());
        SalOrder sourceOrder = null;
        if (request.getSourceOrderId() != null) {
            sourceOrder = requireAuditedOrder(request.getSourceOrderId());
            if (!sourceOrder.getWarehouseId().equals(request.getWarehouseId())) {
                throw new BizException(ErrorCode.WAREHOUSE_MISMATCH_SOURCE,
                        "带源出库单仓库必须与订单仓库一致（§8-12 对称）");
            }
        }
        SalOutbound doc = new SalOutbound();
        doc.setDocNo(docNoService.nextNo(DocType.SAL_OUTBOUND));
        doc.setStatus(DocStatus.DRAFT);
        doc.setDocDate(LocalDate.now());
        doc.setCurrency("CNY");
        doc.setCustomerId(request.getCustomerId());
        doc.setWarehouseId(request.getWarehouseId());
        doc.setSourceOrderId(request.getSourceOrderId());
        doc.setCarrier(request.getCarrier());
        doc.setTrackingNo(request.getTrackingNo());
        doc.setFinFlag(0);
        outboundMapper.insert(doc);
        replaceItems(doc.getId(), request.getItems(), sourceOrder);
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, OutboundSaveRequest request) {
        SalOutbound doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
        SalOrder sourceOrder = doc.getSourceOrderId() == null
                ? null : orderMapper.selectById(doc.getSourceOrderId());
        replaceItems(id, request.getItems(), sourceOrder);
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        SalOutbound doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        int updated = outboundMapper.update(null, Wrappers.<SalOutbound>lambdaUpdate()
                .eq(SalOutbound::getId, id).eq(SalOutbound::getStatus, DocStatus.DRAFT)
                .set(SalOutbound::getStatus, DocStatus.AUDITED)
                .set(SalOutbound::getAuditBy, currentUser.userId())
                .set(SalOutbound::getAuditTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        List<SalOutboundItem> items = itemsOf(id);
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "出库单无明细行");
        }
        // 源绑定复核 + 超发预检（§8-5；权威防线在 addDoneQty 条件更新）
        Map<Long, BigDecimal> bySourceLine = validateSourceBinding(doc, items);
        if (countLockGuard.counting(doc.getWarehouseId(),
                items.stream().map(SalOutboundItem::getSkuId).toList())) {
            throw new BizException(ErrorCode.COUNT_LOCK_CONFLICT, "盘点中，禁止出入库审核");
        }
        List<MoveLine> lines = items.stream().map(item -> {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            if (item.getQty() == null || item.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "出库数量必须大于 0");
            }
            Long lotId = resolveLot(item, p);
            if (p.snManaged()) {
                requireIntegerQty(item.getQty(), "管SN商品出库数量必须为整数");
                if (item.getSnList() == null || item.getSnList().size() != item.getQty().intValueExact()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "管SN商品SN数量必须与出库量一致");
                }
            }
            item.setLotId(lotId);
            item.setAmount(item.getPrice() == null ? null
                    : item.getQty().multiply(item.getPrice()).setScale(2, RoundingMode.HALF_UP));
            itemMapper.updateById(item);
            return new MoveLine(item.getSkuId(), item.getQty(), item.getPrice(), lotId,
                    item.getSnList(), item.getId());
        }).toList();
        // 核销先行：释放本单占用后，门面的可用量校验才对"锁定内的行"放行
        for (Map.Entry<Long, BigDecimal> entry : bySourceLine.entrySet()) {
            stockService.release(new LockRef(DocType.SAL_ORDER, doc.getSourceOrderId(), entry.getKey()),
                    entry.getValue());
        }
        stockService.outbound(new MoveCmd(doc.getWarehouseId(),
                new BizRef(BizType.SALE_OUT, DocType.SAL_OUTBOUND, doc.getId(), doc.getDocNo()), lines));
        // done 原子累加：并发同订单行双审核时后到者 0 行 → 50001，整单（含核销/出库）回滚
        for (SalOutboundItem item : items) {
            if (item.getSourceOrderItemId() == null) {
                continue;
            }
            int rows = orderItemMapper.addDoneQty(item.getSourceOrderItemId(), item.getQty());
            if (rows == 0) {
                throw new BizException(ErrorCode.EXCEED_ORDER_REMAIN,
                        "超出订单行剩余量（含并发冲突）: 本行 " + item.getQty().stripTrailingZeros().toPlainString());
            }
            completeIfAllTerminal(orderItemMapper.selectById(item.getSourceOrderItemId()).getDocId());
        }
        events.publishEvent(new SaleOutboundAuditedEvent(doc.getId(), doc.getDocNo(), DocEvent.Action.AUDITED));
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidOutbound(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因必填");
        }
        SalOutbound doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        if (doc.getStatus() == DocStatus.DRAFT) {
            int draftVoided = outboundMapper.update(null, Wrappers.<SalOutbound>lambdaUpdate()
                    .eq(SalOutbound::getId, id).eq(SalOutbound::getStatus, DocStatus.DRAFT)
                    .set(SalOutbound::getStatus, DocStatus.VOIDED)
                    .set(SalOutbound::getVoidBy, currentUser.userId())
                    .set(SalOutbound::getVoidTime, LocalDateTime.now())
                    .set(SalOutbound::getVoidReason, reason));
            if (draftVoided == 0) {
                throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
            }
            return;
        }
        Long downstream = returnMapper.selectCount(Wrappers.<SalReturn>lambdaQuery()
                .eq(SalReturn::getSourceOutboundId, id)
                .ne(SalReturn::getStatus, DocStatus.VOIDED));
        if (downstream > 0) {
            throw new BizException(ErrorCode.VOID_BLOCKED_BY_DOWNSTREAM,
                    "存在未作废的下游退货单，请先作废退货单");
        }
        int updated = outboundMapper.update(null, Wrappers.<SalOutbound>lambdaUpdate()
                .eq(SalOutbound::getId, id).eq(SalOutbound::getStatus, DocStatus.AUDITED)
                .set(SalOutbound::getStatus, DocStatus.VOIDED)
                .set(SalOutbound::getVoidBy, currentUser.userId())
                .set(SalOutbound::getVoidTime, LocalDateTime.now())
                .set(SalOutbound::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        stockService.reverse(new ReverseCmd(DocType.SAL_OUTBOUND, id));
        // 锁定冲正（§4.2-6 + 审查 C2/I3）：恢复原锁行 remain（restore，不新插行）；
        // 已关闭行(40)不重锁——库存回补回可用量即可，关闭行不得复活锁定。
        // reverse 已回补 qty，开关 off 时重锁可用量恒充足（账本 Task4 数学命题）。
        List<Long> affectedOrderIds = new ArrayList<>();
        for (SalOutboundItem item : itemsOf(id)) {
            if (item.getSourceOrderItemId() == null || doc.getSourceOrderId() == null) {
                continue;
            }
            SalOrderItem src = orderItemMapper.selectById(item.getSourceOrderItemId());
            if (src != null && src.getLineStatus() != 40) {
                stockService.restore(new LockRef(DocType.SAL_ORDER, doc.getSourceOrderId(),
                        item.getSourceOrderItemId()), item.getQty());
            }
            orderItemMapper.subDoneQty(item.getSourceOrderItemId(), item.getQty());
            if (src != null && !affectedOrderIds.contains(src.getDocId())) {
                affectedOrderIds.add(src.getDocId());
            }
        }
        for (Long orderId : affectedOrderIds) {
            Long reopened = orderItemMapper.selectCount(Wrappers.<SalOrderItem>lambdaQuery()
                    .eq(SalOrderItem::getDocId, orderId).eq(SalOrderItem::getLineStatus, 10));
            if (reopened > 0) {
                orderMapper.update(null, Wrappers.<SalOrder>lambdaUpdate()
                        .eq(SalOrder::getId, orderId)
                        .eq(SalOrder::getStatus, DocStatus.COMPLETED)
                        .set(SalOrder::getStatus, DocStatus.AUDITED));
            }
        }
        events.publishEvent(new SaleOutboundAuditedEvent(doc.getId(), doc.getDocNo(), DocEvent.Action.VOIDED));
    }

    /** 退货源单下拉：已审核出库单裁剪视图（权限见 SaleOutboundController#options） */
    public List<SaleDocOptionVO> auditedOptions() {
        return outboundMapper.selectList(Wrappers.<SalOutbound>lambdaQuery()
                        .eq(SalOutbound::getStatus, DocStatus.AUDITED).orderByDesc(SalOutbound::getId))
                .stream().map(o -> new SaleDocOptionVO(
                        o.getId(), o.getDocNo(), o.getCustomerId(), o.getWarehouseId()))
                .toList();
    }

    public PageResult<SalOutbound> page(int pageNum, int pageSize, String keyword) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && scope.isEmpty()) {
            PageResult<SalOutbound> empty = new PageResult<>();
            empty.setTotal(0);
            empty.setRecords(List.of());
            return empty;
        }
        Page<SalOutbound> page = outboundMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<SalOutbound>lambdaQuery()
                        .in(scope != null, SalOutbound::getWarehouseId, scope == null ? Set.of() : scope)
                        .like(keyword != null && !keyword.isBlank(), SalOutbound::getDocNo, keyword)
                        .orderByDesc(SalOutbound::getId));
        return PageResult.of(page);
    }

    public OutboundVO get(Long id) {
        SalOutbound doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        OutboundVO vo = new OutboundVO();
        vo.setDoc(doc);
        vo.setItems(itemsOf(id));
        return vo;
    }

    // ---------- 内部 ----------

    /** 返回按源行 merge 的本单量（预检 + 核销复用）；无源单携带源行即拒 */
    private Map<Long, BigDecimal> validateSourceBinding(SalOutbound doc, List<SalOutboundItem> items) {
        Map<Long, BigDecimal> bySourceLine = new HashMap<>();
        if (doc.getSourceOrderId() == null) {
            for (SalOutboundItem item : items) {
                if (item.getSourceOrderItemId() != null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "无源出库单不能携带源订单行");
                }
            }
            return bySourceLine;
        }
        SalOrder order = requireAuditedOrder(doc.getSourceOrderId());
        if (!order.getWarehouseId().equals(doc.getWarehouseId())) {
            throw new BizException(ErrorCode.WAREHOUSE_MISMATCH_SOURCE, "出库仓库与源订单仓库不一致（§8-12 对称）");
        }
        for (SalOutboundItem item : items) {
            if (item.getSourceOrderItemId() == null) {
                continue; // 混合行：无源部分走门面可用量校验
            }
            SalOrderItem src = requireSourceLine(item.getSourceOrderItemId(), order.getId(), item.getSkuId());
            bySourceLine.merge(src.getId(), item.getQty(), BigDecimal::add);
        }
        for (Map.Entry<Long, BigDecimal> entry : bySourceLine.entrySet()) {
            SalOrderItem src = orderItemMapper.selectById(entry.getKey());
            BigDecimal remain = src.getQty().subtract(src.getDoneQty());
            if (entry.getValue().compareTo(remain) > 0) {
                throw new BizException(ErrorCode.EXCEED_ORDER_REMAIN,
                        "超出订单行剩余量: 本单 " + entry.getValue().stripTrailingZeros().toPlainString()
                                + " 剩余 " + remain.stripTrailingZeros().toPlainString());
            }
        }
        return bySourceLine;
    }

    private SalOrder requireAuditedOrder(Long orderId) {
        SalOrder order = orderMapper.selectById(orderId);
        if (order == null || (order.getStatus() != DocStatus.AUDITED && order.getStatus() != DocStatus.COMPLETED)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "源订单不存在或未到已审核状态");
        }
        return order;
    }

    private SalOrderItem requireSourceLine(Long sourceOrderItemId, Long orderId, Long skuId) {
        SalOrderItem src = orderItemMapper.selectById(sourceOrderItemId);
        if (src == null || !src.getDocId().equals(orderId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "源订单行不存在或不属于该订单");
        }
        if (!src.getSkuId().equals(skuId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "出库行商品与源订单行不一致");
        }
        return src;
    }

    /** 出库方向批次只定位既有批次（禁 materialize，M4 I5 同裁决） */
    private Long resolveLot(SalOutboundItem item, ProductReader.ProductInfo p) {
        boolean blankLot = item.getLotNo() == null || item.getLotNo().isBlank();
        if (blankLot) {
            if (p.lotManaged()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管批次商品必须填批号");
            }
            return null;
        }
        LotApi.LotView lot = lotApi.find(item.getSkuId(), item.getLotNo());
        if (lot == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "批次不存在: " + item.getLotNo() + "（出库不能新建批次）");
        }
        return lot.id();
    }

    private static void requireIntegerQty(BigDecimal qty, String message) {
        if (qty == null || qty.stripTrailingZeros().scale() > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
    }

    /** 全行终态→头 COMPLETED（与 SaleOrderService 同语义，内联避免反向依赖） */
    private void completeIfAllTerminal(Long orderId) {
        Long open = orderItemMapper.selectCount(Wrappers.<SalOrderItem>lambdaQuery()
                .eq(SalOrderItem::getDocId, orderId)
                .eq(SalOrderItem::getLineStatus, 10));
        if (open == 0) {
            orderMapper.update(null, Wrappers.<SalOrder>lambdaUpdate()
                    .eq(SalOrder::getId, orderId).eq(SalOrder::getStatus, DocStatus.AUDITED)
                    .set(SalOrder::getStatus, DocStatus.COMPLETED));
        }
    }

    private void replaceItems(Long docId, List<OutboundItemRequest> items, SalOrder sourceOrder) {
        itemMapper.delete(Wrappers.<SalOutboundItem>lambdaQuery().eq(SalOutboundItem::getDocId, docId));
        int lineNo = 1;
        for (OutboundItemRequest item : items) {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            if (item.getSourceOrderItemId() != null) {
                if (sourceOrder == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "无源出库单不能携带源订单行");
                }
                requireSourceLine(item.getSourceOrderItemId(), sourceOrder.getId(), item.getSkuId());
            }
            SalOutboundItem row = new SalOutboundItem();
            row.setDocId(docId);
            row.setLineNo(lineNo++);
            row.setSkuId(item.getSkuId());
            row.setSkuName(p.name());
            row.setUnit(p.unit());
            row.setSourceOrderItemId(item.getSourceOrderItemId());
            row.setQty(item.getQty());
            row.setPrice(item.getPrice());
            row.setAmount(item.getPrice() == null ? null
                    : item.getQty().multiply(item.getPrice()).setScale(2, RoundingMode.HALF_UP));
            row.setLotNo(item.getLotNo());
            row.setSnList(item.getSnList());
            row.setRemark(item.getRemark());
            itemMapper.insert(row);
        }
    }

    private List<SalOutboundItem> itemsOf(Long docId) {
        return itemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, docId).orderByAsc(SalOutboundItem::getLineNo));
    }

    private SalOutbound requireDoc(Long id) {
        SalOutbound doc = outboundMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "出库单不存在");
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
