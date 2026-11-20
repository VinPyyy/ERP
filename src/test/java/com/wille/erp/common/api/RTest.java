package com.wille.erp.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R 统一响应体契约（spec §5.1）：{code, msg, data}；ok → code=0, msg="ok"。
 */
class RTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void okCarriesCodeZeroMsgOkAndData() {
        R<String> r = R.ok("hello");

        assertThat(r).isNotNull();
        assertThat(r.getCode()).isZero();
        assertThat(r.getMsg()).isEqualTo("ok");
        assertThat(r.getData()).isEqualTo("hello");
    }

    @Test
    void okWithoutDataCarriesNullData() {
        R<Void> r = R.ok();

        assertThat(r.getCode()).isZero();
        assertThat(r.getData()).isNull();
    }

    @Test
    void failCarriesBusinessCodeAndMsg() {
        R<Void> r = R.fail(ErrorCode.STOCK_NOT_ENOUGH, "可用量不足");

        assertThat(r.getCode()).isEqualTo(30001);
        assertThat(r.getMsg()).isEqualTo("可用量不足");
        assertThat(r.getData()).isNull();
    }

    @Test
    void serializesToContractJson() throws Exception {
        String json = mapper.writeValueAsString(R.ok(42));

        assertThat(json).contains("\"code\":0");
        assertThat(json).contains("\"msg\":\"ok\"");
        assertThat(json).contains("\"data\":42");
    }

    @Test
    void pageQueryClampsPageSizeTo200AndPageNumTo1() {
        PageQuery query = new PageQuery();
        query.setPageSize(10000);
        query.setPageNum(0);

        assertThat(query.getPageSize()).isEqualTo(200);
        assertThat(query.getPageNum()).isEqualTo(1);
    }
}
