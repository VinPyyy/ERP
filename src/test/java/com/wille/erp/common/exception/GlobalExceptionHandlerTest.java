package com.wille.erp.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.wille.erp.common.api.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理契约（spec §5.1）：
 * BizException → HTTP 200 + 业务 code；参数校验 → 10001 + 首条字段错误；
 * NotLogin → HTTP 401；NotPermission → HTTP 403；兜底 → 200 + code 500 且不泄露堆栈。
 * standalone MockMvc：不起 Spring 上下文、不依赖数据库。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bizExceptionMapsToHttp200WithBusinessCode() throws Exception {
        mockMvc.perform(get("/probe/biz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.STOCK_NOT_ENOUGH))
                .andExpect(jsonPath("$.msg").value("可用量不足"));
    }

    @Test
    void paramViolationMapsTo10001WithFirstFieldError() throws Exception {
        mockMvc.perform(post("/probe/param").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void notLoginMapsToHttp401() throws Exception {
        mockMvc.perform(get("/probe/not-login"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notPermissionMapsToHttp403() throws Exception {
        mockMvc.perform(get("/probe/not-permission"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unexpectedExceptionMapsTo500CodeWithoutStackTraceLeak() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("NullPointerException"))));
    }

    // ---- 探针 controller：仅测试用 ----

    @RestController
    static class ProbeController {

        @Data
        static class ProbeForm {
            @NotBlank
            private String name;
        }

        @GetMapping("/probe/biz")
        public void biz() {
            throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH, "可用量不足");
        }

        @PostMapping("/probe/param")
        public String param(@Valid @RequestBody ProbeForm form) {
            return form.getName();
        }

        @GetMapping("/probe/not-login")
        public void notLogin() {
            throw NotLoginException.newInstance("login", "-1", "未登录", null);
        }

        @GetMapping("/probe/not-permission")
        public void notPermission() {
            throw new NotPermissionException("sale:outbound:audit");
        }

        @GetMapping("/probe/boom")
        public void boom() {
            throw new NullPointerException("secret internal detail");
        }
    }
}
