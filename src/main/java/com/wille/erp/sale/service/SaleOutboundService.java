package com.wille.erp.sale.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.sale.dto.OutboundItemRequest;
import com.wille.erp.sale.dto.OutboundSaveRequest;
import com.wille.erp.sale.dto.OutboundVO;
import com.wille.erp.sale.entity.SalOutbound;
import com.wille.erp.sale.entity.SalOutboundItem;
import com.wille.erp.sale.mapper.SalOutboundItemMapper;
import com.wille.erp.sale.mapper.SalOutboundMapper;
import com.wille.erp.system.api.CurrentUserHolder;
import com.wille.erp.system.api.DocNoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 销售出库单（M5 任务 4 补审核/作废链；本文件当前为草稿创建与查询骨架）。
 * 出库即发货（spec §2.5）：carrier/tracking_no 挂头；审核动库存+核销锁定在任务 4。
 */
@Service
@RequiredArgsConstructor
public class SaleOutboundService {

    private final SalOutboundMapper outboundMapper;
    private final SalOutboundItemMapper itemMapper;
    private final ProductReader productReader;
    private final DocNoService docNoService;
    private final CurrentUserHolder currentUser;

    @Transactional(rollbackFor = Exception.class)
    public Long create(OutboundSaveRequest request) {
        requireWarehouseVisible(request.getWarehouseId());
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
        replaceItems(doc.getId(), request.getItems());
        return doc.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, OutboundSaveRequest request) {
        SalOutbound doc = requireDoc(id);
        requireWarehouseVisible(doc.getWarehouseId());
        if (doc.getStatus() != DocStatus.DRAFT) {
            throw new BizException(ErrorCode.DOC_STATUS_CHANGED, "仅草稿可编辑");
        }
        replaceItems(id, request.getItems());
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

    // ---------- 内部（任务 4 复用） ----------

    protected List<SalOutboundItem> itemsOf(Long docId) {
        return itemMapper.selectList(Wrappers.<SalOutboundItem>lambdaQuery()
                .eq(SalOutboundItem::getDocId, docId).orderByAsc(SalOutboundItem::getLineNo));
    }

    protected SalOutbound requireDoc(Long id) {
        SalOutbound doc = outboundMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "出库单不存在");
        }
        return doc;
    }

    protected void requireWarehouseVisible(Long warehouseId) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
    }

    private void replaceItems(Long docId, List<OutboundItemRequest> items) {
        itemMapper.delete(Wrappers.<SalOutboundItem>lambdaQuery().eq(SalOutboundItem::getDocId, docId));
        int lineNo = 1;
        for (OutboundItemRequest item : items) {
            ProductReader.ProductInfo p = productReader.read(item.getSkuId());
            if (p == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在: " + item.getSkuId());
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

    /** 流水 bizDocItemId 等写库存动作在任务 4 接入门面 */
    protected static BigDecimal amountOf(BigDecimal qty, BigDecimal price) {
        return price == null ? null : qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
    }
}
