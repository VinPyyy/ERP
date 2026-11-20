package com.wille.erp.basedata.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.mapper.BaseProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * basedata 实体 → 对外 ProductInfo 的转换出口（ArchUnit：业务模块只见 api 类型，不见 entity）。
 */
@Service
@RequiredArgsConstructor
public class ProductReaderImpl implements ProductReader {

    private final BaseProductMapper productMapper;

    @Override
    public ProductInfo read(Long skuId) {
        BaseProduct product = productMapper.selectById(skuId);
        if (product == null) {
            return null;
        }
        return toInfo(product);
    }

    @Override
    public ProductInfo readByCode(String skuCode) {
        BaseProduct product = productMapper.selectOne(Wrappers.<BaseProduct>lambdaQuery()
                .eq(BaseProduct::getSkuCode, skuCode));
        return product == null ? null : toInfo(product);
    }

    private static ProductInfo toInfo(BaseProduct product) {
        return new ProductInfo(product.getId(), product.getSkuCode(), product.getName(),
                product.getUnit(), product.getMgmtMode(), product.getShelfLifeDays());
    }

    /** M3 批次/SN 弹窗按名称搜索用；limit 在应用层收敛 */
    public List<BaseProduct> searchByName(String keyword, int limit) {
        return productMapper.selectList(Wrappers.<BaseProduct>lambdaQuery()
                .eq(BaseProduct::getStatus, 1)
                .and(w -> w.like(BaseProduct::getName, keyword).or().like(BaseProduct::getSkuCode, keyword))
                .last("LIMIT " + Math.min(Math.max(limit, 1), 50)));
    }
}
