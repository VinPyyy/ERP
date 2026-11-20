package com.wille.erp.basedata.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.dto.CategorySaveRequest;
import com.wille.erp.basedata.entity.BaseCategory;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.mapper.BaseCategoryMapper;
import com.wille.erp.basedata.mapper.BaseProductMapper;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品类别树：名称全局唯一（Excel 导入按名称定位的前提）；有子类别或被商品引用禁删。
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final BaseCategoryMapper categoryMapper;
    private final BaseProductMapper productMapper;

    @Transactional(rollbackFor = Exception.class)
    public Long create(CategorySaveRequest request) {
        requireNameUnique(request.getName(), null);
        BaseCategory category = new BaseCategory();
        apply(category, request);
        categoryMapper.insert(category);
        return category.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategorySaveRequest request) {
        BaseCategory category = requireCategory(id);
        requireNameUnique(request.getName(), id);
        if (request.getParentId() != null && request.getParentId().equals(id)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "类别不能挂到自身下");
        }
        apply(category, request);
        categoryMapper.updateById(category);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireCategory(id);
        Long children = categoryMapper.selectCount(Wrappers.<BaseCategory>lambdaQuery()
                .eq(BaseCategory::getParentId, id));
        if (children > 0) {
            throw new BizException(ErrorCode.RECORD_REFERENCED, "存在子类别，不可删除");
        }
        Long products = productMapper.selectCount(Wrappers.<BaseProduct>lambdaQuery()
                .eq(BaseProduct::getCategoryId, id));
        if (products > 0) {
            throw new BizException(ErrorCode.RECORD_REFERENCED, "类别下存在商品，不可删除");
        }
        categoryMapper.deleteById(id);
    }

    public List<BaseCategory> listAll() {
        return categoryMapper.selectList(Wrappers.<BaseCategory>lambdaQuery().orderByAsc(BaseCategory::getId));
    }

    private BaseCategory requireCategory(Long id) {
        BaseCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "类别不存在");
        }
        return category;
    }

    private void requireNameUnique(String name, Long excludeId) {
        Long count = categoryMapper.selectCount(Wrappers.<BaseCategory>lambdaQuery()
                .eq(BaseCategory::getName, name)
                .ne(excludeId != null, BaseCategory::getId, excludeId));
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "类别名称已存在: " + name);
        }
    }

    private void apply(BaseCategory category, CategorySaveRequest request) {
        category.setName(request.getName());
        category.setParentId(request.getParentId() == null ? 0L : request.getParentId());
        category.setSortNo(request.getSortNo() == null ? 0 : request.getSortNo());
    }
}
