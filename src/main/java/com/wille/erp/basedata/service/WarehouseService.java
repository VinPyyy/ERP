package com.wille.erp.basedata.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.dto.WarehouseSaveRequest;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.basedata.mapper.BaseWarehouseMapper;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 仓库管理：编码唯一；≤10 为规模假设不设硬限。
 * TODO M3/M6：删除前校验库存引用（经事件监听，同 ProductReferenceCheckEvent 模式）。
 */
@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final BaseWarehouseMapper warehouseMapper;

    @Transactional(rollbackFor = Exception.class)
    public Long create(WarehouseSaveRequest request) {
        requireCodeUnique(request.getCode(), null);
        BaseWarehouse warehouse = new BaseWarehouse();
        apply(warehouse, request);
        warehouseMapper.insert(warehouse);
        return warehouse.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, WarehouseSaveRequest request) {
        BaseWarehouse warehouse = requireWarehouse(id);
        requireCodeUnique(request.getCode(), id);
        apply(warehouse, request);
        warehouseMapper.updateById(warehouse);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireWarehouse(id);
        warehouseMapper.deleteById(id);
    }

    public List<BaseWarehouse> listAll() {
        return warehouseMapper.selectList(Wrappers.<BaseWarehouse>lambdaQuery().orderByAsc(BaseWarehouse::getId));
    }

    private BaseWarehouse requireWarehouse(Long id) {
        BaseWarehouse warehouse = warehouseMapper.selectById(id);
        if (warehouse == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仓库不存在");
        }
        return warehouse;
    }

    private void requireCodeUnique(String code, Long excludeId) {
        Long count = warehouseMapper.selectCount(Wrappers.<BaseWarehouse>lambdaQuery()
                .eq(BaseWarehouse::getCode, code)
                .ne(excludeId != null, BaseWarehouse::getId, excludeId));
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仓库编码已存在: " + code);
        }
        // 已删行仍占唯一索引：提前拦截，否则 insert 撞 Duplicate entry 变 500（username-lifecycle 通则）
        if (warehouseMapper.countDeletedByCode(code) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该仓库编码曾被使用: " + code);
        }
    }

    private void apply(BaseWarehouse warehouse, WarehouseSaveRequest request) {
        warehouse.setCode(request.getCode());
        warehouse.setName(request.getName());
        warehouse.setAddress(request.getAddress());
        warehouse.setKeeper(request.getKeeper());
        warehouse.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        warehouse.setSortNo(request.getSortNo() == null ? 0 : request.getSortNo());
        warehouse.setRemark(request.getRemark());
    }
}
