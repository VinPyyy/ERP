package com.wille.erp.basedata.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.dto.PartnerQuery;
import com.wille.erp.basedata.dto.PartnerSaveRequest;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.mapper.BaseSupplierMapper;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 供应商管理：与客户结构对称（GLOSSARY"客户 / 供应商"）。
 * TODO M4：删除前经事件校验采购单据引用。
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final BaseSupplierMapper supplierMapper;

    @Transactional(rollbackFor = Exception.class)
    /** 下拉引用数据：启用供应商 id+编码+名称（docs/system/reference-options.md） */
    public List<BaseSupplier> listAllActive() {
        return supplierMapper.selectList(Wrappers.<BaseSupplier>lambdaQuery()
                .eq(BaseSupplier::getStatus, 1).orderByAsc(BaseSupplier::getId));
    }

    public Long create(PartnerSaveRequest request) {
        requireCodeUnique(request.getCode(), null);
        BaseSupplier supplier = new BaseSupplier();
        apply(supplier, request);
        supplierMapper.insert(supplier);
        return supplier.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, PartnerSaveRequest request) {
        BaseSupplier supplier = requireSupplier(id);
        requireCodeUnique(request.getCode(), id);
        apply(supplier, request);
        supplierMapper.updateById(supplier);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireSupplier(id);
        supplierMapper.deleteById(id);
    }

    public PageResult<BaseSupplier> page(PartnerQuery query) {
        Page<BaseSupplier> page = supplierMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<BaseSupplier>lambdaQuery()
                        .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                                w -> w.like(BaseSupplier::getCode, query.getKeyword())
                                        .or().like(BaseSupplier::getName, query.getKeyword()))
                        .eq(query.getStatus() != null, BaseSupplier::getStatus, query.getStatus())
                        .orderByDesc(BaseSupplier::getCreateTime));
        return new PageResult<>(page.getTotal(), page.getRecords());
    }

    private BaseSupplier requireSupplier(Long id) {
        BaseSupplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "供应商不存在");
        }
        return supplier;
    }

    private void requireCodeUnique(String code, Long excludeId) {
        Long count = supplierMapper.selectCount(Wrappers.<BaseSupplier>lambdaQuery()
                .eq(BaseSupplier::getCode, code)
                .ne(excludeId != null, BaseSupplier::getId, excludeId));
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "供应商编码已存在: " + code);
        }
        // 已删行仍占唯一索引：提前拦截，否则 insert 撞 Duplicate entry 变 500（username-lifecycle 通则）
        if (supplierMapper.countDeletedByCode(code) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该供应商编码曾被使用: " + code);
        }
    }

    private void apply(BaseSupplier supplier, PartnerSaveRequest request) {
        supplier.setCode(request.getCode());
        supplier.setName(request.getName());
        supplier.setContact(request.getContact());
        supplier.setPhone(request.getPhone());
        supplier.setAddress(request.getAddress());
        supplier.setTaxNo(request.getTaxNo());
        supplier.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        supplier.setRemark(request.getRemark());
    }
}
