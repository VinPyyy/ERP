package com.wille.erp.common.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 鉴权拦截器（内存模式，无 Redis——ADR/spec §8-18）。
 * 双层：全局 auth 函数 = StpUtil.checkLogin()（/api/** 一律要求登录态，白名单除外）；
 * 注解 = @SaCheckPermission 权限点细粒度校验。
 *
 * 教训（2026-11-21）：无参 new SaInterceptor() 只做注解校验、不做全局登录校验——
 * "登录即可"的 /options 端点因无注解曾被匿名访问。凡不加权限注解的端点，
 * 登录防线必须由这里的 auth 函数兜底，另建议显式 @SaCheckLogin 自我文档化。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/crypto");
    }
}
