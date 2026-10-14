package com.datagenerator.web.dto;

/**
 * 连接探查请求：测通 / 列库表 / 列字段共用。
 */
public record ConnectionProbeRequest(
        String type,
        String url,
        String path,
        String username,
        String password,
        String schema,
        String pattern) {}
