package com.wille.erp.basedata.dto;

import com.wille.erp.common.api.PageQuery;
import com.wille.erp.common.enums.MgmtMode;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProductQuery extends PageQuery {

    /** sku_code/name/barcode 模糊 */
    private String keyword;
    private Long categoryId;
    private MgmtMode mgmtMode;
    private Integer status;
}
