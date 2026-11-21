package com.wille.erp.sale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.sale.entity.SalOutboundItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SalOutboundItemMapper extends BaseMapper<SalOutboundItem> {

    /**
     * 配额串行化锁（M5 审查 I1）：退货审核聚合校验前锁源出库行，
     * 并发审核同出库行的两张退货在此排队，后到者的配额聚合读到前者已提交的 AUDITED 单。
     */
    @Select("select * from sal_outbound_item where id = #{id} and deleted = 0 for update")
    SalOutboundItem selectForUpdate(@Param("id") Long id);
}
