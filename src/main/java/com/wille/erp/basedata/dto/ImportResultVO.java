package com.wille.erp.basedata.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Excel 导入结果：有错不落库（全量校验）；errors 给精确行号（数据行从 1 起，不含表头）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportResultVO {

    private int total;
    private int successCount;
    private List<RowError> errors;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        private int row;
        private String reason;
    }

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}
