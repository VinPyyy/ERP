package com.wille.erp.basedata.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wille.erp.basedata.dto.ProductQuery;
import com.wille.erp.basedata.dto.ProductSaveRequest;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.mapper.BaseProductMapper;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.api.PageResult;
import com.wille.erp.common.event.ProductReferenceCheckEvent;
import com.wille.erp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品管理：编码唯一；管批次必填保质期；mgmt_mode 变更/删除经 ProductReferenceCheckEvent
 * 校验库存/流水引用（spec §8-15，inventory 监听方否决，异常原路回滚）。
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final BaseProductMapper productMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public Long create(ProductSaveRequest request) {
        validate(request);
        requireCodeUnique(request.getSkuCode(), null);
        BaseProduct product = new BaseProduct();
        apply(product, request);
        productMapper.insert(product);
        return product.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ProductSaveRequest request) {
        validate(request);
        BaseProduct product = requireProduct(id);
        requireCodeUnique(request.getSkuCode(), id);
        if (product.getMgmtMode() != request.getMgmtMode()) {
            // 模式变更 = 历史流水语义崩坏风险：有引用即拒绝（spec §8-15）
            eventPublisher.publishEvent(new ProductReferenceCheckEvent(id, request.getMgmtMode().getCode(), request.getSkuCode()));
        }
        apply(product, request);
        productMapper.updateById(product);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BaseProduct product = requireProduct(id);
        eventPublisher.publishEvent(new ProductReferenceCheckEvent(id, null, product.getSkuCode()));
        productMapper.deleteById(id);
    }

    public BaseProduct get(Long id) {
        return requireProduct(id);
    }

    public PageResult<BaseProduct> page(ProductQuery query) {
        Page<BaseProduct> page = productMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<BaseProduct>lambdaQuery()
                        .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                                w -> w.like(BaseProduct::getSkuCode, query.getKeyword())
                                        .or().like(BaseProduct::getName, query.getKeyword())
                                        .or().like(BaseProduct::getBarcode, query.getKeyword()))
                        .eq(query.getCategoryId() != null, BaseProduct::getCategoryId, query.getCategoryId())
                        .eq(query.getMgmtMode() != null, BaseProduct::getMgmtMode, query.getMgmtMode())
                        .eq(query.getStatus() != null, BaseProduct::getStatus, query.getStatus())
                        .orderByDesc(BaseProduct::getCreateTime));
        return new PageResult<>(page.getTotal(), page.getRecords());
    }

    private BaseProduct requireProduct(Long id) {
        BaseProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "商品不存在");
        }
        return product;
    }

    private void validate(ProductSaveRequest request) {
        if (request.getMgmtMode().lotManaged()
                && (request.getShelfLifeDays() == null || request.getShelfLifeDays() <= 0)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "管批次商品必须填写保质期天数");
        }
    }

    private void requireCodeUnique(String skuCode, Long excludeId) {
        Long count = productMapper.selectCount(Wrappers.<BaseProduct>lambdaQuery()
                .eq(BaseProduct::getSkuCode, skuCode)
                .ne(excludeId != null, BaseProduct::getId, excludeId));
        if (count > 0) {
            throw new BizException(ErrorCode.PRODUCT_CODE_DUPLICATED, "商品编码已存在: " + skuCode);
        }
    }

    private void apply(BaseProduct product, ProductSaveRequest request) {
        product.setSkuCode(request.getSkuCode());
        product.setName(request.getName());
        product.setSpec(request.getSpec());
        product.setUnit(request.getUnit());
        product.setBarcode(request.getBarcode());
        product.setCategoryId(request.getCategoryId());
        product.setMgmtMode(request.getMgmtMode());
        product.setShelfLifeDays(request.getShelfLifeDays());
        product.setExpireWarnDays(request.getExpireWarnDays());
        product.setRefSalePrice(request.getRefSalePrice());
        product.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        product.setRemark(request.getRemark());
    }

    /** 导出用：按条件全量（不分页） */
    List<BaseProduct> listForExport(ProductQuery query) {
        return productMapper.selectList(Wrappers.<BaseProduct>lambdaQuery()
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(BaseProduct::getSkuCode, query.getKeyword())
                                .or().like(BaseProduct::getName, query.getKeyword()))
                .eq(query.getCategoryId() != null, BaseProduct::getCategoryId, query.getCategoryId())
                .eq(query.getMgmtMode() != null, BaseProduct::getMgmtMode, query.getMgmtMode())
                .eq(query.getStatus() != null, BaseProduct::getStatus, query.getStatus())
                .orderByAsc(BaseProduct::getSkuCode));
    }
}
