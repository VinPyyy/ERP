package com.wille.erp.basedata.dto;

import com.wille.erp.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PartnerQuery extends PageQuery {

    /** code/name 模糊 */
    private String keyword;
    private Integer status;
}
