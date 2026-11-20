package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.enums.SnStatus;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.inventory.dto.FlowQuery;
import com.wille.erp.inventory.dto.FlowVO;
import com.wille.erp.inventory.dto.LotStockVO;
import com.wille.erp.inventory.dto.SnAdjustRequest;
import com.wille.erp.inventory.dto.SnQuery;
import com.wille.erp.inventory.dto.SnVO;
import com.wille.erp.inventory.dto.StockQuery;
import com.wille.erp.inventory.dto.StockVO;
import com.wille.erp.inventory.entity.InvFlow;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.entity.InvLotStock;
import com.wille.erp.inventory.entity.InvSn;
import com.wille.erp.inventory.entity.InvStock;
import com.wille.erp.inventory.mapper.InvFlowMapper;
import com.wille.erp.inventory.mapper.InvLotMapper;
import com.wille.erp.inventory.mapper.InvLotStockMapper;
import com.wille.erp.inventory.mapper.InvSnMapper;
import com.wille.erp.inventory.mapper.InvStockMapper;
import com.wille.erp.system.api.CurrentUserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * 库存查询（M3c 任务 1）：汇总→批次→SN 三级 + 流水 + SN 人工调整。
 * 仓库数据权限一律经 CurrentUserHolder.warehouseScope()（spec §5）；
 * 成本列按 inventory:cost:view 后端置 null（脱敏在服务端，非前端隐藏）。
 */
@Service
@RequiredArgsConstructor
public class InventoryQueryService {

    private final InvStockMapper stockMapper;
    private final InvLotStockMapper lotStockMapper;
    private final InvLotMapper lotMapper;
    private final InvSnMapper snMapper;
    private final InvFlowMapper flowMapper;
    private final ProductReader productReader;
    private final CurrentUserHolder currentUser;

    public PageResult<StockVO> pageStocks(StockQuery query) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && scope.isEmpty()) {
            return emptyPage();
        }
        List<Long> skuIds = null;
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            skuIds = productReader.searchIds(query.getKeyword().trim());
            if (skuIds.isEmpty()) {
                return emptyPage();
            }
        }
        Page<InvStock> page = stockMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<InvStock>lambdaQuery()
                        .in(scope != null, InvStock::getWarehouseId, scope == null ? List.of() : scope)
                        .in(skuIds != null, InvStock::getSkuId, skuIds == null ? List.of() : skuIds)
                        .orderByAsc(InvStock::getWarehouseId)
                        .orderByAsc(InvStock::getSkuId));
        boolean canViewCost = currentUser.hasPerm("inventory:cost:view");
        List<StockVO> records = page.getRecords().stream().map(row -> {
            StockVO vo = new StockVO();
            vo.setWarehouseId(row.getWarehouseId());
            vo.setSkuId(row.getSkuId());
            vo.setQty(row.getQty());
            vo.setLockedQty(row.getLockedQty());
            vo.setAvailableQty(row.getQty().subtract(row.getLockedQty()));
            if (canViewCost) {
                vo.setCostPrice(row.getCostPrice());
                vo.setCostAmount(row.getCostAmount());
            }
            ProductReader.ProductInfo p = productReader.read(row.getSkuId());
            if (p != null) {
                vo.setSkuCode(p.skuCode());
                vo.setSkuName(p.name());
                vo.setUnit(p.unit());
            }
            return vo;
        }).toList();
        return new PageResult<>(page.getTotal(), records);
    }

    public List<LotStockVO> lotStocks(Long warehouseId, Long skuId) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
        return lotStockMapper.selectList(Wrappers.<InvLotStock>lambdaQuery()
                        .eq(InvLotStock::getWarehouseId, warehouseId)
                        .eq(InvLotStock::getSkuId, skuId)
                        .orderByAsc(InvLotStock::getId)).stream()
                .map(row -> {
                    LotStockVO vo = new LotStockVO();
                    vo.setLotId(row.getLotId());
                    vo.setQty(row.getQty());
                    InvLot lot = lotMapper.selectById(row.getLotId());
                    if (lot != null) {
                        vo.setLotNo(lot.getLotNo());
                        vo.setProductionDate(lot.getProductionDate());
                        vo.setExpiryDate(lot.getExpiryDate());
                    }
                    return vo;
                }).toList();
    }

    public PageResult<SnVO> pageSns(SnQuery query) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && scope.isEmpty()) {
            return emptyPage();
        }
        Page<InvSn> page = snMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<InvSn>lambdaQuery()
                        .in(scope != null, InvSn::getWarehouseId, scope == null ? List.of() : scope)
                        .eq(query.getSkuId() != null, InvSn::getSkuId, query.getSkuId())
                        .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                                InvSn::getStatus, parseStatus(query.getStatus()))
                        .orderByDesc(InvSn::getId));
        return new PageResult<>(page.getTotal(), page.getRecords().stream().map(row -> {
            SnVO vo = new SnVO();
            vo.setId(row.getId());
            vo.setSkuId(row.getSkuId());
            vo.setSnCode(row.getSnCode());
            vo.setLotId(row.getLotId());
            vo.setStatus(row.getStatus() == null ? null : row.getStatus().name());
            vo.setWarehouseId(row.getWarehouseId());
            vo.setInDocNo(row.getInDocNo());
            vo.setInTime(row.getInTime());
            vo.setOutDocNo(row.getOutDocNo());
            vo.setOutTime(row.getOutTime());
            vo.setRemark(row.getRemark());
            return vo;
        }).toList());
    }

    public PageResult<FlowVO> pageFlows(FlowQuery query) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && scope.isEmpty()) {
            return emptyPage();
        }
        Page<InvFlow> page = flowMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<InvFlow>lambdaQuery()
                        .in(scope != null, InvFlow::getWarehouseId, scope == null ? List.of() : scope)
                        .eq(query.getSkuId() != null, InvFlow::getSkuId, query.getSkuId())
                        .eq(query.getBizType() != null && !query.getBizType().isBlank(),
                                InvFlow::getBizType, parseBizType(query.getBizType()))
                        .like(query.getBizDocNo() != null && !query.getBizDocNo().isBlank(),
                                InvFlow::getBizDocNo, query.getBizDocNo())
                        .ge(query.getStartDate() != null, InvFlow::getCreateTime,
                                query.getStartDate() == null ? null : query.getStartDate().atStartOfDay())
                        .le(query.getEndDate() != null, InvFlow::getCreateTime,
                                query.getEndDate() == null ? null : LocalDateTime.of(query.getEndDate(), LocalTime.MAX))
                        .orderByDesc(InvFlow::getId));
        return new PageResult<>(page.getTotal(), page.getRecords().stream().map(row -> {
            FlowVO vo = new FlowVO();
            vo.setId(row.getId());
            vo.setWarehouseId(row.getWarehouseId());
            vo.setSkuId(row.getSkuId());
            vo.setLotId(row.getLotId());
            vo.setQty(row.getQty());
            vo.setPrice(row.getPrice());
            vo.setCostPrice(row.getCostPrice());
            vo.setCostAmount(row.getCostAmount());
            vo.setBalanceQty(row.getBalanceQty());
            vo.setBalanceCostAmount(row.getBalanceCostAmount());
            vo.setBalanceCostPrice(row.getBalanceCostPrice());
            vo.setBizType(row.getBizType() == null ? null : row.getBizType().name());
            vo.setBizDocNo(row.getBizDocNo());
            vo.setCreateTime(row.getCreateTime());
            ProductReader.ProductInfo p = productReader.read(row.getSkuId());
            if (p != null) {
                vo.setSkuCode(p.skuCode());
                vo.setSkuName(p.name());
            }
            return vo;
        }).toList());
    }

    /** SN 人工调整（spec §8-9）：仅状态/仓库 + 原因留痕；数量差由盘点修正，不写流水 */
    public void adjustSn(Long id, SnAdjustRequest request) {
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "调整原因必填");
        }
        InvSn sn = snMapper.selectById(id);
        if (sn == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "SN 不存在");
        }
        SnStatus target = parseStatus(request.getStatus());
        if (target == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态只能为 IN_STOCK 或 OUT");
        }
        if (target == SnStatus.IN_STOCK) {
            Set<Long> scope = currentUser.warehouseScope();
            if (request.getWarehouseId() == null
                    || (scope != null && !scope.contains(request.getWarehouseId()))) {
                throw new BizException(ErrorCode.PARAM_INVALID, "回库必须指定可见仓库");
            }
        }
        snMapper.update(null, Wrappers.<InvSn>lambdaUpdate()
                .eq(InvSn::getId, id)
                .set(InvSn::getStatus, target)
                .set(target == SnStatus.IN_STOCK, InvSn::getWarehouseId, request.getWarehouseId())
                .set(target == SnStatus.OUT, InvSn::getWarehouseId, null)
                .set(InvSn::getRemark, request.getReason()));
    }

    private static SnStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SnStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static com.wille.erp.common.enums.BizType parseBizType(String raw) {
        try {
            return com.wille.erp.common.enums.BizType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <T> PageResult<T> emptyPage() {
        PageResult<T> result = new PageResult<>();
        result.setTotal(0);
        result.setRecords(List.of());
        return result;
    }

}
