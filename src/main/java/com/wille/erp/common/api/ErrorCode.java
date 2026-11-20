package com.wille.erp.common.api;

/**
 * 业务错误码分段（spec §5）：
 * 1xxxx 通用/认证，2xxxx 基础资料，3xxxx 库存，4xxxx 采购，5xxxx 销售，6xxxx 系统。
 * 401（未登录）/403（无权限）走 HTTP 状态码，不占业务码。
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    /** 成功 */
    public static final int OK = 0;
    /** 兜底内部错误 */
    public static final int INTERNAL = 500;

    // 1xxxx 通用/认证
    public static final int PARAM_INVALID = 10001;
    public static final int CONCURRENT_CONFLICT = 10002;
    public static final int DOC_STATUS_CHANGED = 10003;
    public static final int LOGIN_FAILED = 10004;
    public static final int USER_DISABLED = 10005;
    /** 加密挑战过期/无效，前端应重取挑战重试 */
    public static final int LOGIN_CHALLENGE_INVALID = 10006;
    /** 连续失败触发限流锁定 */
    public static final int LOGIN_LOCKED = 10007;
    public static final int NOT_LOGIN = 10401;
    public static final int NO_PERMISSION = 10403;

    // 2xxxx 基础资料（M2 启用）
    public static final int PRODUCT_CODE_DUPLICATED = 20001;
    public static final int MGMT_MODE_LOCKED = 20002;
    public static final int RECORD_REFERENCED = 20003;
    public static final int IMPORT_HAS_ERRORS = 20004;

    // 3xxxx 库存（M3 启用）
    public static final int STOCK_NOT_ENOUGH = 30001;
    public static final int COUNT_LOCK_CONFLICT = 30002;
    public static final int VOID_STOCK_CONSUMED = 30003;
    public static final int SN_STATUS_INVALID = 30004;
    public static final int SN_DUPLICATED_IN_STOCK = 30005;
    public static final int LOT_STOCK_NOT_ENOUGH = 30006;
    public static final int OPENING_DUPLICATED = 30007;

    // 4xxxx 采购（M4 启用）
    public static final int OVER_RECEIVE_EXCEEDED = 40001;
    public static final int RETURN_EXCEED_SOURCE = 40002;
    public static final int VOID_BLOCKED_BY_DOWNSTREAM = 40003;

    // 5xxxx 销售（M5 启用）
    public static final int EXCEED_ORDER_REMAIN = 50001;
    public static final int WAREHOUSE_MISMATCH_SOURCE = 50002;

    // 6xxxx 系统
    public static final int DOC_NO_FAIL = 60001;
    public static final int PARAM_NOT_FOUND = 60002;
    /** 路由不存在（HTTP 404，方案 A 补充：不把 404 记成服务器故障） */
    public static final int API_NOT_FOUND = 60003;
}
