package com.wille.erp.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.inventory.entity.InvSn;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface InvSnMapper extends BaseMapper<InvSn> {

    /** 入库冲正删除本单新建 SN：out_doc_id is null 限定"从未出库=新建"，翻回行走恢复分支（审查 C1） */
    @Delete("delete from inv_sn where sku_id=#{skuId} and in_doc_type=#{docType} and in_doc_id=#{docId} "
            + "and out_doc_id is null")
    int hardDeleteByInDoc(@Param("skuId") Long skuId, @Param("docType") String docType,
                          @Param("docId") Long docId);
}
