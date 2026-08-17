package com.astradb.server.api;

import com.astradb.core.ingest.SnapshotIngestor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 统一异常映射：结构化错误体 {@code {code, message, timestamp, path}}。
 * {@code message} 保持既有语义（页面/客户端可直接展示）；{@code code} 供编程处理。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 错误码（稳定标识，供客户端编程分支）。 */
    public static final String CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String CODE_INGEST_REJECTED = "INGEST_REJECTED";
    public static final String CODE_UNPARSEABLE_BODY = "UNPARSEABLE_BODY";
    public static final String CODE_STORAGE_ERROR = "STORAGE_ERROR";
    public static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    public record ApiError(String code, String message, long timestamp, String path) {
        static ApiError of(String code, String message, HttpServletRequest req) {
            return new ApiError(code, message, System.currentTimeMillis(), req.getRequestURI());
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(CODE_INVALID_ARGUMENT, e.getMessage(), req));
    }

    @ExceptionHandler(SnapshotIngestor.IngestException.class)
    public ResponseEntity<ApiError> ingestRejected(SnapshotIngestor.IngestException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(CODE_INGEST_REJECTED, e.getMessage(), req));
    }

    /** 请求体 JSON 解析失败 → 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(CODE_UNPARSEABLE_BODY,
                        "请求体无法解析: " + e.getMostSpecificCause().getMessage(), req));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiError> ioError(IOException e, HttpServletRequest req) {
        log.error("IO 错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(CODE_STORAGE_ERROR, "存储错误: " + e.getMessage(), req));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest req) {
        log.error("未预期错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(CODE_INTERNAL_ERROR, "内部错误: " + e.getMessage(), req));
    }
}
