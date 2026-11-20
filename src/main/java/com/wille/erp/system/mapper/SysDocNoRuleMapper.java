package com.wille.erp.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wille.erp.system.entity.SysDocNoRule;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysDocNoRuleMapper extends BaseMapper<SysDocNoRule> {

    /** 取号行锁（spec §2.1）：必须在事务内调用，锁住规则行后自增 current_seq */
    @Select("SELECT * FROM sys_doc_no_rule WHERE doc_type = #{docType} AND deleted = 0 FOR UPDATE")
    SysDocNoRule selectForUpdateByDocType(@Param("docType") String docType);
}
