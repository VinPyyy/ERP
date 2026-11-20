package com.wille.erp.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.inventory.entity.InvOpening;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface InvOpeningMapper extends BaseMapper<InvOpening> {

    /** 编辑/导入与审核/作废并发时锁单头（审查 I2）：行替换必须与状态迁移互斥 */
    @Select("select * from inv_opening where id=#{id} and deleted=0 for update")
    InvOpening selectForUpdate(@Param("id") Long id);
}
