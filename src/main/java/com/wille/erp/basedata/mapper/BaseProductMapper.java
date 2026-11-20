package com.wille.erp.basedata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.basedata.entity.BaseProduct;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BaseProductMapper extends BaseMapper<BaseProduct> {

    /** 已删行仍占 DB 唯一索引 uk_product_sku_code：显式计数供"曾被使用"友好拦截（docs/system/username-lifecycle.md 通则） */
    @Select("select count(*) from base_product where sku_code = #{code} and deleted = 1")
    long countDeletedByCode(@Param("code") String code);
}
