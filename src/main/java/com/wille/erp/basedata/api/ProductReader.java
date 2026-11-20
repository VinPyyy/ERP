package com.wille.erp.basedata.api;

import com.wille.erp.common.enums.MgmtMode;

/**
 * 商品只读门面（basedata 对外 API，spec §1）：
 * purchase/sale/inventory 经此读管理维度与保质期，禁止直接 import entity/mapper。
 */
public interface ProductReader {

    /** SKU 不存在返回 null（调用方校验） */
    ProductInfo read(Long skuId);

    record ProductInfo(Long skuId, String skuCode, String name, String unit,
                       MgmtMode mgmtMode, Integer shelfLifeDays) {
        public boolean lotManaged() {
            return mgmtMode != null && mgmtMode.lotManaged();
        }

        public boolean snManaged() {
            return mgmtMode != null && mgmtMode.snManaged();
        }
    }
}
