package com.wille.erp.common.api;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体：{code, msg, data}（spec §5.1）。
 * code=0 成功；业务错误一律 HTTP 200 + 分段业务码（401/403 除外，走 HTTP 语义）。
 */
@Data
public class R<T> implements Serializable {

    public static final String MSG_OK = "ok";

    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok() {
        return build(ErrorCode.OK, MSG_OK, null);
    }

    public static <T> R<T> ok(T data) {
        return build(ErrorCode.OK, MSG_OK, data);
    }

    public static <T> R<T> fail(int code, String msg) {
        return build(code, msg, null);
    }

    private static <T> R<T> build(int code, String msg, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }
}
