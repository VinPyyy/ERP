package com.wille.erp.basedata.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.dto.PartnerQuery;
import com.wille.erp.basedata.dto.PartnerSaveRequest;
import com.wille.erp.basedata.entity.BaseCustomer;
import com.wille.erp.basedata.mapper.BaseCustomerMapper;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户管理：编码唯一；删除的单据引用校验 M5 销售单据上线后经事件监听补齐（TODO 见类注释）。
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final BaseCustomerMapper customerMapper;

    @Transactional(rollbackFor = Exception.class)
    public Long create(PartnerSaveRequest request) {
        requireCodeUnique(request.getCode(), null);
        BaseCustomer customer = new BaseCustomer();
        apply(customer, request);
        customerMapper.insert(customer);
        return customer.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, PartnerSaveRequest request) {
        BaseCustomer customer = requireCustomer(id);
        requireCodeUnique(request.getCode(), id);
        apply(customer, request);
        customerMapper.updateById(customer);
    }

    /** TODO M5：删除前经 PartnerReferenceCheckEvent 校验销售单据引用 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireCustomer(id);
        customerMapper.deleteById(id);
    }

    public PageResult<BaseCustomer> page(PartnerQuery query) {
        Page<BaseCustomer> page = customerMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<BaseCustomer>lambdaQuery()
                        .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                                w -> w.like(BaseCustomer::getCode, query.getKeyword())
                                        .or().like(BaseCustomer::getName, query.getKeyword()))
                        .eq(query.getStatus() != null, BaseCustomer::getStatus, query.getStatus())
                        .orderByDesc(BaseCustomer::getCreateTime));
        return new PageResult<>(page.getTotal(), page.getRecords());
    }

    private BaseCustomer requireCustomer(Long id) {
        BaseCustomer customer = customerMapper.selectById(id);
        if (customer == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "客户不存在");
        }
        return customer;
    }

    private void requireCodeUnique(String code, Long excludeId) {
        Long count = customerMapper.selectCount(Wrappers.<BaseCustomer>lambdaQuery()
                .eq(BaseCustomer::getCode, code)
                .ne(excludeId != null, BaseCustomer::getId, excludeId));
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "客户编码已存在: " + code);
        }
        // 已删行仍占唯一索引：提前拦截，否则 insert 撞 Duplicate entry 变 500（username-lifecycle 通则）
        if (customerMapper.countDeletedByCode(code) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该客户编码曾被使用: " + code);
        }
    }

    private void apply(BaseCustomer customer, PartnerSaveRequest request) {
        customer.setCode(request.getCode());
        customer.setName(request.getName());
        customer.setContact(request.getContact());
        customer.setPhone(request.getPhone());
        customer.setAddress(request.getAddress());
        customer.setTaxNo(request.getTaxNo());
        customer.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        customer.setRemark(request.getRemark());
    }
}
