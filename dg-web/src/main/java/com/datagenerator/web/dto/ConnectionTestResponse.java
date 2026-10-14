package com.datagenerator.web.dto;

/**
 * 连接测通结果。失败时由全局异常处理返回 400，不走本响应体。
 */
public record ConnectionTestResponse(boolean ok, String message) {

    public static ConnectionTestResponse success() {
        return new ConnectionTestResponse(true, null);
    }
}
