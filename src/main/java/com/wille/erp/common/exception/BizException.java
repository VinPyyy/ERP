package com.wille.erp.common.exception;

import lombok.Getter;

/**
 * 业务异常：携带分段业务码（ErrorCode），由 GlobalExceptionHandler 映射为 HTTP 200 + code。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(int code, String message) {
        return new BizException(code, message);
    }
}
