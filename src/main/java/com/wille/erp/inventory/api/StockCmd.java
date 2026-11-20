package com.wille.erp.inventory.api;

import com.wille.erp.common.enums.BizType;
import com.wille.erp.common.enums.DocType;

import java.math.BigDecimal;
import java.util.List;

/**
 * StockService 命令载荷（spec §1：库存不认识单据语义，只接受 Cmd）。
 * 外层 Cmd 的 warehouseId 为单仓操作；多仓场景（调拨）由调用方拆成两次门面调用。
 */
public final class StockCmd {

    private StockCmd() {
    }

    /** 变动溯源：哪个业务动作、哪张单 */
    public record BizRef(BizType bizType, DocType docType, Long docId, String docNo) {
    }

    /**
     * 出入库行。
     * lotId：管批次 SKU 必填；snList：管 SN SKU 必填且 size == qty；
     * price：入库=入账价（可 0），出库忽略（按均价结转）；
     * bizDocItemId：单据行 id，流水溯源用。
     */
    public record MoveLine(Long skuId, BigDecimal qty, BigDecimal price,
                           Long lotId, List<String> snList, Long bizDocItemId) {
    }

    public record MoveCmd(Long warehouseId, BizRef bizRef, List<MoveLine> lines) {
    }

    public record LockLine(Long skuId, BigDecimal qty) {
    }

    /** 锁定归属：一期恒 (SAL_ORDER, docId, itemId) */
    public record LockRef(DocType lockDocType, Long lockDocId, Long lockItemId) {
    }

    public record LockCmd(Long warehouseId, LockRef ref, List<LockLine> lines) {
    }

    /** 作废冲正：取该单未被冲正的全部流水，按快照反向 */
    public record ReverseCmd(DocType docType, Long docId) {
    }
}
