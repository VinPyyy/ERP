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
import com.wille.erp.purchase.dto.DocOptionVO;
import com.wille.erp.purchase.dto.InboundItemRequest;
import com.wille.erp.purchase.dto.InboundSaveRequest;
import com.wille.erp.purchase.dto.InboundVO;
import com.wille.erp.purchase.entity.PurInbound;
import com.wille.erp.purchase.entity.PurInboundItem;
import com.wille.erp.purchase.entity.PurOrder;
import com.wille.erp.purchase.entity.PurOrderItem;
import com.wille.erp.purchase.entity.PurReturn;
import com.wille.erp.purchase.mapper.PurInboundItemMapper;
import com.wille.erp.purchase.mapper.PurInboundMapper;
import com.wille.erp.purchase.mapper.PurOrderItemMapper;
import com.wille.erp.purchase.mapper.PurOrderMapper;
import com.wille.erp.purchase.mapper.PurReturnMapper;
import com.wille.erp.system.api.ConfigService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采购入库单（spec §2.4/§4.1）：审核动库存（StockService.inbound PUR_IN）+ 批次物化 + SN；
 * 溢收校验 done+本次 ≤ qty×(1+ratio)（40001）；带 source 仓库硬校验（§8-12）；
 * 作废 = 下游退货校验 + reverse 快照冲正 + done_qty 回减/行状态回退（§4.2）。
 */
@Service
@RequiredArgsConstructor
public class InboundService {

    private final PurInboundMapper inboundMapper;
    private final PurInboundItemMapper itemMapper;
    private final PurOrderMapper orderMapper;
    private final PurOrderItemMapper orderItemMapper;
    private final PurReturnMapper returnMapper;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final ConfigService configService;
    private final StockService stockService;
    private final LotApi lotApi;
    private final CountLockGuard countLockGuard;
    private final CurrentUserHolder currentUser;

    @Transactional(rollbackFor = Exception.class)
    public Long create(InboundSaveRequest request) {
        requireWarehouseVisible(request.getWarehouseId());
        PurOrder sourceOrder = null;
        if (request.getSourceOrderId() != null) {
            sourceOrder = orderMapper.selectById(request.getSourceOrderId());
            if (sourceOrder == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "源订单不存在");
            }
            // M4 审查 I3：源订单必须已审核/已完成（草稿、作废订单不可作为源）
            if (sourceOrder.getStatus() != DocStatus.AUDITED && sourceOrder.getStatus() != DocStatus.COMPLETED) {
                throw new BizException(ErrorCode.PARAM_INVALID, "源订单须已审核");
            }
            if (!sourceOrder.getWarehouseId().equals(request.getWarehouseId())) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "带源单的入库单仓库必须与订单仓库一致（§8-12）");
            }
        }
        PurInbound doc = new PurInbound();
        doc.setDocNo(docNoService.nextNo(DocType.PUR_INBOUND));
        doc.setStatus(DocStatus.DRAFT);
        doc.setDocDate(LocalDate.now());
        doc.setCurrency("CNY");
        doc.setSupplierId(request.getSupplierId());
        doc.setWarehouseId(request.getWarehouseId());
        doc.setSourceOrderId(request.getSourceOrderId());
        doc.setFinFlag(0);
        inboundMapper.insert(doc);
        replaceItems(doc.getId(), request.getItems(), sourceOrder);
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, InboundSaveRequest request) {
        PurInbound doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
        PurOrder sourceOrder = doc.getSourceOrderId() == null ? null : orderMapper.selectById(doc.getSourceOrderId());
        replaceItems(id, request.getItems(), sourceOrder);
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        PurInbound doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        // M4 审查 I3：审核时复核源绑定（源单状态/仓库/行归属/SKU），保存后的篡改或漂移在这里兜底
        validateSourceBinding(doc);
        int updated = inboundMapper.update(null, Wrappers.<PurInbound>lambdaUpdate()
                .eq(PurInbound::getId, id).eq(PurInbound::getStatus, DocStatus.DRAFT)
                .set(PurInbound::getStatus, DocStatus.AUDITED)
                .set(PurInbound::getAuditBy, currentUser.userId())
                .set(PurInbound::getAuditTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        List<PurInboundItem> items = itemsOf(id);
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "入库单无明细行");
        }
        // 盘点锁（ADR-0004）
        if (countLockGuard.counting(doc.getWarehouseId(),
                items.stream().map(PurInboundItem::getSkuId).toList())) {
            throw new BizException(ErrorCode.COUNT_LOCK_CONFLICT, "盘点中，禁止出入库审核");
        }
        // 溢收校验（§2.4）：按源行累计
        checkOverReceive(items);
        // 形态校验 + 批次物化 + 库存入账
        List<MoveLine> lines = items.stream().map(item -> {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            if (item.getQty() == null || item.getQty().signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "入库数量必须大于 0");
            }
            if (p.lotManaged() && (item.getLotNo() == null || item.getLotNo().isBlank())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管批次商品必须填批号");
            }
            if (p.snManaged()) {
                requireIntegerQty(item.getQty(), "管SN商品入库数量必须为整数");
                if (item.getSnList() == null || item.getSnList().size() != item.getQty().intValueExact()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "管SN商品SN数量必须与入库量一致");
                }
            }
            Long lotId = (item.getLotNo() == null || item.getLotNo().isBlank())
                    ? null : lotApi.materialize(item.getSkuId(), item.getLotNo(), item.getProductionDate());
            item.setLotId(lotId);
            item.setAmount(item.getPrice() == null ? null
                    : item.getQty().multiply(item.getPrice()).setScale(2, RoundingMode.HALF_UP));
            itemMapper.updateById(item);
            return new MoveLine(item.getSkuId(), item.getQty(), item.getPrice(), lotId,
                    item.getSnList(), item.getId());
        }).toList();
        stockService.inbound(new MoveCmd(doc.getWarehouseId(),
                new BizRef(BizType.PUR_IN, DocType.PUR_INBOUND, doc.getId(), doc.getDocNo()), lines));
        // done_qty 累计与行/头完成（§3e 采购侧）
        applyDoneQty(items);
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidInbound(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因必填");
        }
        PurInbound doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        // M4 审查 I6：草稿可作废（建错的单不必"先审核动库存再作废"）
        if (doc.getStatus() == DocStatus.DRAFT) {
            int draftVoided = inboundMapper.update(null, Wrappers.<PurInbound>lambdaUpdate()
                    .eq(PurInbound::getId, id).eq(PurInbound::getStatus, DocStatus.DRAFT)
                    .set(PurInbound::getStatus, DocStatus.VOIDED)
                    .set(PurInbound::getVoidBy, currentUser.userId())
                    .set(PurInbound::getVoidTime, LocalDateTime.now())
                    .set(PurInbound::getVoidReason, reason));
            if (draftVoided == 0) {
                throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
            }
            return;
        }
        Long downstream = returnMapper.selectCount(Wrappers.<PurReturn>lambdaQuery()
                .eq(PurReturn::getSourceInboundId, id)
                .ne(PurReturn::getStatus, DocStatus.VOIDED));
        if (downstream > 0) {
            throw new BizException(ErrorCode.VOID_BLOCKED_BY_DOWNSTREAM,
                    "存在未作废的下游退货单，请先作废退货单");
        }
        int updated = inboundMapper.update(null, Wrappers.<PurInbound>lambdaUpdate()
                .eq(PurInbound::getId, id).eq(PurInbound::getStatus, DocStatus.AUDITED)
                .set(PurInbound::getStatus, DocStatus.VOIDED)
                .set(PurInbound::getVoidBy, currentUser.userId())
                .set(PurInbound::getVoidTime, LocalDateTime.now())
                .set(PurInbound::getVoidReason, reason));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "单据状态已变化，请刷新后重试");
        }
        stockService.reverse(new ReverseCmd(DocType.PUR_INBOUND, id));
        // done_qty 原子回减 + 行回退由 SQL CASE 判定（M4 审查 C1/I2）
        List<Long> affectedOrderIds = new ArrayList<>();
        for (PurInboundItem item : itemsOf(id)) {
            if (item.getSourceOrderItemId() == null) {
                continue;
            }
            PurOrderItem orderItem = orderItemMapper.selectById(item.getSourceOrderItemId());
            if (orderItem == null) {
                continue;
            }
            orderItemMapper.subDoneQty(orderItem.getId(), item.getQty());
            if (!affectedOrderIds.contains(orderItem.getDocId())) {
                affectedOrderIds.add(orderItem.getDocId());
            }
        }
        // 头回退仅在有行被真正重开时执行——溢收后部分作废 done 仍 ≥ qty → 头保持 COMPLETED
        for (Long orderId : affectedOrderIds) {
            Long reopened = orderItemMapper.selectCount(Wrappers.<PurOrderItem>lambdaQuery()
                    .eq(PurOrderItem::getDocId, orderId).eq(PurOrderItem::getLineStatus, 10));
            if (reopened > 0) {
                orderMapper.update(null, Wrappers.<PurOrder>lambdaUpdate()
                        .eq(PurOrder::getId, orderId)
                        .eq(PurOrder::getStatus, DocStatus.COMPLETED)
                        .set(PurOrder::getStatus, DocStatus.AUDITED));
            }
        }
    }

    public PurInbound requireDoc(Long id) {
        PurInbound doc = inboundMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "入库单不存在");
        }
        return doc;
    }

    private List<PurInboundItem> itemsOf(Long docId) {
        return itemMapper.selectList(Wrappers.<PurInboundItem>lambdaQuery()
                .eq(PurInboundItem::getDocId, docId).orderByAsc(PurInboundItem::getLineNo));
    }

    /** 溢收预检（友好报错用）；并发下的权威防线是 applyDoneQty 的条件原子更新（M4 审查 C1） */
    private void checkOverReceive(List<PurInboundItem> items) {
        BigDecimal ratio = configService.getDecimal(ConfigService.KEY_OVER_RECEIVE_RATIO);
        Map<Long, BigDecimal> bySourceLine = new HashMap<>();
        for (PurInboundItem item : items) {
            if (item.getSourceOrderItemId() != null) {
                bySourceLine.merge(item.getSourceOrderItemId(), item.getQty(), BigDecimal::add);
            }
        }
        for (Map.Entry<Long, BigDecimal> entry : bySourceLine.entrySet()) {
            PurOrderItem orderItem = orderItemMapper.selectById(entry.getKey());
            if (orderItem == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "源订单行不存在");
            }
            BigDecimal limit = orderItem.getQty()
                    .multiply(BigDecimal.ONE.add(ratio)).setScale(4, RoundingMode.HALF_UP);
            if (orderItem.getDoneQty().add(entry.getValue()).compareTo(limit) > 0) {
                throw new BizException(ErrorCode.OVER_RECEIVE_EXCEEDED,
                        "溢收超限：累计 " + orderItem.getDoneQty().add(entry.getValue())
                                .stripTrailingZeros().toPlainString() + " > 上限 "
                                + limit.stripTrailingZeros().toPlainString());
            }
        }
    }

    /**
     * done_qty 原子累计（M4 审查 C1）：上限判断放进 UPDATE 的 WHERE，
     * 两张入库单并发审核同一源行时后到者影响 0 行 → 40001 拒绝，库存随事务回滚。
     */
    private void applyDoneQty(List<PurInboundItem> items) {
        BigDecimal ratio = configService.getDecimal(ConfigService.KEY_OVER_RECEIVE_RATIO);
        for (PurInboundItem item : items) {
            if (item.getSourceOrderItemId() == null) {
                continue;
            }
            PurOrderItem orderItem = orderItemMapper.selectById(item.getSourceOrderItemId());
            if (orderItem == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "源订单行不存在");
            }
            BigDecimal limit = orderItem.getQty()
                    .multiply(BigDecimal.ONE.add(ratio)).setScale(4, RoundingMode.HALF_UP);
            int rows = orderItemMapper.addDoneQty(orderItem.getId(), item.getQty(), limit);
            if (rows == 0) {
                throw new BizException(ErrorCode.OVER_RECEIVE_EXCEEDED,
                        "溢收超限（含并发冲突）：本单 " + item.getQty().stripTrailingZeros().toPlainString()
                                + " 超过余量，上限 " + limit.stripTrailingZeros().toPlainString());
            }
            completeIfAllTerminal(orderItem.getDocId());
        }
    }

    /** 全行终态（30/40）→ 头 AUDITED→COMPLETED；与 OrderService.closeItem 同语义，内联避免循环依赖 */
    private void completeIfAllTerminal(Long orderId) {
        Long open = orderItemMapper.selectCount(Wrappers.<PurOrderItem>lambdaQuery()
                .eq(PurOrderItem::getDocId, orderId)
                .eq(PurOrderItem::getLineStatus, 10));
        if (open == 0) {
            orderMapper.update(null, Wrappers.<PurOrder>lambdaUpdate()
                    .eq(PurOrder::getId, orderId).eq(PurOrder::getStatus, DocStatus.AUDITED)
                    .set(PurOrder::getStatus, DocStatus.COMPLETED));
        }
    }

    private void replaceItems(Long docId, List<InboundItemRequest> items, PurOrder sourceOrder) {
        itemMapper.delete(Wrappers.<PurInboundItem>lambdaQuery().eq(PurInboundItem::getDocId, docId));
        int lineNo = 1;
        for (InboundItemRequest item : items) {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
            }
            // M4 审查 I3：源行归属与 SKU 一致性在保存时先挡一道（审核时 validateSourceBinding 兜底复核）
            if (item.getSourceOrderItemId() != null) {
                if (sourceOrder == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "无源入库单不能携带源订单行");
                }
                requireSourceLine(item.getSourceOrderItemId(), sourceOrder.getId(), item.getSkuId());
            }
            PurInboundItem row = new PurInboundItem();
            row.setDocId(docId);
            row.setLineNo(lineNo++);
            row.setSkuId(item.getSkuId());
            row.setSkuName(p.name());
            row.setUnit(p.unit());
            row.setSourceOrderItemId(item.getSourceOrderItemId());
            row.setQty(item.getQty());
            row.setPrice(item.getPrice());
            row.setLotNo(item.getLotNo());
            row.setProductionDate(item.getProductionDate());
            row.setSnList(item.getSnList());
            row.setRemark(item.getRemark());
            itemMapper.insert(row);
        }
    }

    /** 退货源单下拉：已审核入库单裁剪视图（方案 A，权限见 InboundController#options） */
    public List<DocOptionVO> auditedOptions() {
        return inboundMapper.selectList(Wrappers.<PurInbound>lambdaQuery()
                        .eq(PurInbound::getStatus, DocStatus.AUDITED).orderByDesc(PurInbound::getId))
                .stream().map(o -> new DocOptionVO(o.getId(), o.getDocNo(), o.getSupplierId(), o.getWarehouseId()))
                .toList();
    }

    public PageResult<PurInbound> page(int pageNum, int pageSize, String keyword) {
        // M4 审查 I1：仓库数据权限（spec §5）；零仓库用户查空而非放行
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && scope.isEmpty()) {
            PageResult<PurInbound> empty = new PageResult<>();
            empty.setTotal(0);
            empty.setRecords(List.of());
            return empty;
        }
        Page<PurInbound> page = inboundMapper.selectPage(
                new Page<>(pageNum, pageSize),
                Wrappers.<PurInbound>lambdaQuery()
                        .in(scope != null, PurInbound::getWarehouseId, scope == null ? Set.of() : scope)
                        .like(keyword != null && !keyword.isBlank(), PurInbound::getDocNo, keyword)
                        .orderByDesc(PurInbound::getId));
        return PageResult.of(page);
    }

    public InboundVO get(Long id) {
        PurInbound doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        InboundVO vo = new InboundVO();
        vo.setDoc(doc);
        vo.setItems(itemsOf(id));
        return vo;
    }

    // ---------- 校验（M4 审查修复批次） ----------

    /** 审核时源绑定复核：源单存在且已审核/已完成、仓库一致、每行源行归属该单且 SKU 一致 */
    private PurOrder validateSourceBinding(PurInbound doc) {
        if (doc.getSourceOrderId() == null) {
            for (PurInboundItem item : itemsOf(doc.getId())) {
                if (item.getSourceOrderItemId() != null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "无源入库单不能携带源订单行");
                }
            }
            return null;
        }
        PurOrder order = orderMapper.selectById(doc.getSourceOrderId());
        if (order == null || (order.getStatus() != DocStatus.AUDITED && order.getStatus() != DocStatus.COMPLETED)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "源订单不存在或未到已审核状态");
        }
        if (!order.getWarehouseId().equals(doc.getWarehouseId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "入库仓库与源订单仓库不一致（§8-12）");
        }
        // 混合行合法：无源行不参与溢收/done 联动（§2.4），仅挂源行校验归属
        for (PurInboundItem item : itemsOf(doc.getId())) {
            if (item.getSourceOrderItemId() != null) {
                requireSourceLine(item.getSourceOrderItemId(), order.getId(), item.getSkuId());
            }
        }
        return order;
    }

    private void requireSourceLine(Long sourceOrderItemId, Long orderId, Long skuId) {
        PurOrderItem src = orderItemMapper.selectById(sourceOrderItemId);
        if (src == null || !src.getDocId().equals(orderId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "源订单行不存在或不属于该订单");
        }
        if (!src.getSkuId().equals(skuId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "入库行商品与源订单行不一致");
        }
    }

    private void requireWarehouseVisible(Long warehouseId) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
    }

    /** M4 审查 M5：intValueExact 前先挡非整数，避免 ArithmeticException 500 */
    private static void requireIntegerQty(BigDecimal qty, String message) {
        if (qty == null || qty.stripTrailingZeros().scale() > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
    }
}
