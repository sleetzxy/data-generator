package com.datagenerator.plugins.postgresql;

/**
 * PostgreSQL JDBC 连接 URL 辅助。
 */
final class PostgreSqlConnectionUrls {

    private PostgreSqlConnectionUrls() {
    }

    /**
     * 为批量写入启用驱动端 multi-value INSERT 重写（未显式配置时默认开启）。
     */
    static String withWriterDefaults(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (url.contains("reWriteBatchedInserts=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "reWriteBatchedInserts=true";
    }
}
