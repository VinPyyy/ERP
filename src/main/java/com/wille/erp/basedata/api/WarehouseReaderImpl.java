package com.wille.erp.basedata.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.basedata.mapper.BaseWarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WarehouseReaderImpl implements WarehouseReader {

    private final BaseWarehouseMapper warehouseMapper;

    @Override
    public boolean existsActive(Long warehouseId) {
        return warehouseMapper.selectCount(Wrappers.<BaseWarehouse>lambdaQuery()
                .eq(BaseWarehouse::getId, warehouseId)
                .eq(BaseWarehouse::getStatus, 1)) > 0;
    }

    @Override
    public Set<Long> findInvalid(Set<Long> warehouseIds) {
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            return Set.of();
        }
        List<Long> valid = warehouseMapper.selectList(Wrappers.<BaseWarehouse>lambdaQuery()
                        .select(BaseWarehouse::getId)
                        .in(BaseWarehouse::getId, warehouseIds)
                        .eq(BaseWarehouse::getStatus, 1)).stream()
                .map(BaseWarehouse::getId)
                .toList();
        return warehouseIds.stream().filter(id -> !valid.contains(id)).collect(Collectors.toSet());
    }
}
