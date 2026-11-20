package com.wille.erp.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.inventory.entity.InvFlow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;

public interface InvFlowMapper extends BaseMapper<InvFlow> {

    /**
     * 未配对冲正的外部流水计数（审查 I8 裁决）：净零对（原流水+冲正行）不阻断期初作废，
     * 否则 ADR-0003"逆序作废"会死路。排除本单 OPENING 原流水；调用方保证 skuIds 非空。
     */
    @Select("<script>select count(*) from inv_flow f where f.warehouse_id=#{warehouseId} and f.deleted=0 "
            + "and f.reverse_flow_id is null "
            + "and not (f.biz_type='OPENING' and f.biz_doc_id=#{openingDocId}) "
            + "and f.sku_id in <foreach collection='skuIds' item='s' open='(' separator=',' close=')'>#{s}</foreach> "
            + "and not exists (select 1 from inv_flow r where r.reverse_flow_id=f.id and r.deleted=0)"
            + "</script>")
    long countUnreversedForeignFlows(@Param("warehouseId") Long warehouseId,
                                     @Param("skuIds") Collection<Long> skuIds,
                                     @Param("openingDocId") Long openingDocId);
}
