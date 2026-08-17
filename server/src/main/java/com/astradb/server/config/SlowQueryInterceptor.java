package com.astradb.server.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 慢查询日志：记录超过阈值的 API 请求（method/path/耗时/表参数）。
 */
public class SlowQueryInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger("SLOW_QUERY");
    private static final String ATTR_START = SlowQueryInterceptor.class.getName() + ".startNanos";

    private final long thresholdMs;

    public SlowQueryInterceptor(long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        Object start = request.getAttribute(ATTR_START);
        if (!(start instanceof Long startNanos)) {
            return;
        }
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        if (ms >= thresholdMs) {
            String table = request.getParameter("table");
            log.warn("慢查询: {} {} 耗时 {} ms（阈值 {} ms）table={}", request.getMethod(),
                    request.getRequestURI(), ms, thresholdMs, table == null ? "-" : table);
        }
    }
}
