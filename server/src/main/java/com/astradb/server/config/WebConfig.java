package com.astradb.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册慢查询日志拦截器（阈值可配，默认 500ms）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final long slowQueryThresholdMs;

    public WebConfig(@Value("${astradb.slow-query-threshold-ms:500}") long slowQueryThresholdMs) {
        this.slowQueryThresholdMs = slowQueryThresholdMs;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SlowQueryInterceptor(slowQueryThresholdMs))
                .addPathPatterns("/api/**");
    }
}
