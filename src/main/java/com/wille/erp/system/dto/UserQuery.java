package com.wille.erp.system.dto;

import com.wille.erp.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {

    /** 用户名/姓名模糊 */
    private String keyword;
    private Integer status;
}
