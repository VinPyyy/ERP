package com.wille.erp.basedata.dto;

/**
 * 档案下拉引用数据（docs/system/reference-options.md）：登录即可访问的裁剪视图，
 * 刻意不含联系人/价格/成本等业务敏感字段。
 */
public final class OptionVOs {

    private OptionVOs() {
    }

    public record WarehouseOption(Long id, String code, String name) {
    }

    public record PartnerOption(Long id, String code, String name) {
    }

    public record ProductOption(Long id, String skuCode, String name, String unit) {
    }
}
