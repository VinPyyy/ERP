package com.wille.erp.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.api.ProductReader;
import com.wille.erp.basedata.api.WarehouseReader;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.enums.BizType;
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
 * 库存查询（M3c）：汇总→批次→SN 三级 + 流水 + SN 人工调整。
 * 数据权限：读端点一律 warehouseScope()（null=全部、空集=查空、参数与 scope 取交集）；
 * 成本列（stocks 与 flows）按 inventory:cost:view 服务端置 null（审查 C2 裁决：流水同口径）。
 */
@Service
@RequiredArgsConstructor
public class InventoryQueryService {

    private static final int KEYWORD_HIT_LIMIT = 200;

    private final InvStockMapper stockMapper;
    private final InvLotStockMapper lotStockMapper;
    private final InvLotMapper lotMapper;
    private final InvSnMapper snMapper;
    private final InvFlowMapper flowMapper;
    private final ProductReader productReader;
    private final WarehouseReader warehouseReader;
    private final CurrentUserHolder currentUser;

    public PageResult<StockVO> pageStocks(StockQuery query) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && scope.isEmpty()) {
            return emptyPage();
        }
        List<Long> skuIds = null;
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            skuIds = productReader.searchIds(query.getKeyword().trim());
            if (skuIds.size() >= KEYWORD_HIT_LIMIT) {
                throw new BizException(ErrorCode.PARAM_INVALID, "关键字命中商品过多，请缩小范围");
            }
            if (skuIds.isEmpty()) {
                return emptyPage();
            }
        }
        Page<InvStock> page = stockMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<InvStock>lambdaQuery()
                        .in(scope != null, InvStock::getWarehouseId, scope == null ? List.of() : scope)
                        .eq(query.getWarehouseId() != null, InvStock::getWarehouseId, query.getWarehouseId())
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
                vo.setLotManaged(p.lotManaged());
                vo.setSnManaged(p.snManaged());
            }
            return vo;
        }).toList();
        return new PageResult<>(page.getTotal(), records);
    }

    public List<LotStockVO> lotStocks(Long warehouseId, Long skuId) {
        requireWarehouseVisible(warehouseId);
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
                        .eq(query.getWarehouseId() != null, InvSn::getWarehouseId, query.getWarehouseId())
                        .eq(query.getSkuId() != null, InvSn::getSkuId, query.getSkuId())
                        .eq(query.getLotId() != null, InvSn::getLotId, query.getLotId())
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
        BizType bizType = parseBizType(query.getBizType());
        Page<InvFlow> page = flowMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<InvFlow>lambdaQuery()
                        .in(scope != null, InvFlow::getWarehouseId, scope == null ? List.of() : scope)
                        .eq(query.getWarehouseId() != null, InvFlow::getWarehouseId, query.getWarehouseId())
                        .eq(query.getSkuId() != null, InvFlow::getSkuId, query.getSkuId())
                        .eq(bizType != null, InvFlow::getBizType, bizType)
                        .like(query.getBizDocNo() != null && !query.getBizDocNo().isBlank(),
                                InvFlow::getBizDocNo, query.getBizDocNo())
                        .ge(query.getStartDate() != null, InvFlow::getCreateTime,
                                query.getStartDate() == null ? null : query.getStartDate().atStartOfDay())
                        .le(query.getEndDate() != null, InvFlow::getCreateTime,
                                query.getEndDate() == null ? null : LocalDateTime.of(query.getEndDate(), LocalTime.MAX))
                        .orderByDesc(InvFlow::getId));
        boolean canViewCost = currentUser.hasPerm("inventory:cost:view");
        return new PageResult<>(page.getTotal(), page.getRecords().stream().map(row -> {
            FlowVO vo = new FlowVO();
            vo.setId(row.getId());
            vo.setWarehouseId(row.getWarehouseId());
            vo.setSkuId(row.getSkuId());
            vo.setLotId(row.getLotId());
            vo.setQty(row.getQty());
            vo.setPrice(canViewCost ? row.getPrice() : null);
            vo.setBalanceQty(row.getBalanceQty());
            vo.setBizType(row.getBizType() == null ? null : row.getBizType().name());
            vo.setBizDocNo(row.getBizDocNo());
            vo.setCreateTime(row.getCreateTime());
            if (canViewCost) {
                vo.setCostPrice(row.getCostPrice());
                vo.setCostAmount(row.getCostAmount());
                vo.setBalanceCostAmount(row.getBalanceCostAmount());
                vo.setBalanceCostPrice(row.getBalanceCostPrice());
            }
            ProductReader.ProductInfo p = productReader.read(row.getSkuId());
            if (p != null) {
                vo.setSkuCode(p.skuCode());
                vo.setSkuName(p.name());
            }
            return vo;
        }).toList());
    }

    /**
     * SN 人工调整（spec §8-9）：逃生门操作，写侧数据权限从严（审查 C3）——
     * 在库 SN 的源仓必须在调用者 scope 内；已出库 SN（warehouse=NULL）仅全仓用户（admin）可调（裁决：
     * 出库态归属需按单据追溯，绑仓用户经销售退货处理更正确）；回库目标仓必须存在且启用（I3）。
     * 用实体更新以触发 update_by/update_time 审计填充（I4）。
     */
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
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null) {
            if (sn.getWarehouseId() == null || !scope.contains(sn.getWarehouseId())) {
                throw new BizException(ErrorCode.NO_PERMISSION, "无该 SN 所在仓库的数据权限");
            }
        }
        if (target == SnStatus.IN_STOCK) {
            if (request.getWarehouseId() == null || !warehouseReader.existsActive(request.getWarehouseId())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "回库仓库不存在或已停用");
            }
            if (scope != null && !scope.contains(request.getWarehouseId())) {
                throw new BizException(ErrorCode.NO_PERMISSION, "无回库目标仓库的数据权限");
            }
        }
        sn.setStatus(target);
        sn.setRemark(request.getReason());
        if (target == SnStatus.IN_STOCK) {
            sn.setWarehouseId(request.getWarehouseId());
        }
        snMapper.updateById(sn);
        if (target == SnStatus.OUT) {
            // updateById 忽略 null 字段，出库清空仓库需显式 set（审计字段已由上一步刷新）
            snMapper.update(null, Wrappers.<InvSn>lambdaUpdate()
                    .eq(InvSn::getId, id)
                    .set(InvSn::getWarehouseId, null));
        }
    }

    private void requireWarehouseVisible(Long warehouseId) {
        Set<Long> scope = currentUser.warehouseScope();
        if (scope != null && !scope.contains(warehouseId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "无该仓库数据权限");
        }
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

    private static BizType parseBizType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BizType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "业务类型非法: " + raw);
        }
    }

    private static <T> PageResult<T> emptyPage() {
        PageResult<T> result = new PageResult<>();
        result.setTotal(0);
        result.setRecords(List.of());
        return result;
    }
}
