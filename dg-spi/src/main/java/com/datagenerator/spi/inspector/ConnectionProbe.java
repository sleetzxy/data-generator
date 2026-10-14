package com.datagenerator.spi.inspector;

/**
 * 连接探查参数，供 {@link ConnectionInspector} 使用。
 */
public record ConnectionProbe(
        String type,
        String url,
        String path,
        String username,
        String password) {}
