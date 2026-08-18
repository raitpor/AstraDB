package com.astradb.client;

/**
 * 客户端异常：结构化错误码（与 server 统一错误码对齐）。
 */
public class ClientException extends RuntimeException {

    private final String code;

    public ClientException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
