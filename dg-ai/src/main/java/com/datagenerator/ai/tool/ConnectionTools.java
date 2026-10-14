package com.datagenerator.ai.tool;

import com.datagenerator.ai.client.DgWebClient;
import com.datagenerator.ai.client.DgWebClient.ConnectionTestResult;
import com.datagenerator.ai.client.DgWebClient.RemoteColumn;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 连接探查能力工具：测通、列表、取字段。无阶段机，由 LLM ReAct 按需调用。
 */
@Component
public class ConnectionTools {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTools.class);

    private final DgWebClient client;

    public ConnectionTools(Optional<DgWebClient> client) {
        this.client = client.orElse(null);
    }

    private String emitAndReturn(ToolEmitter emitter, String result) {
        if (emitter != null) {
            emitter.emit(ToolResultBlock.text(result));
        }
        return result;
    }

    private boolean hasClient() {
        return client != null;
    }

    @Tool(name = "testConnection", description = "测通数据源连接。勿将密码对用户复述")
    public String testConnection(
            @ToolParam(name = "type", description = "连接类型：postgresql / clickhouse / csv") String type,
            @ToolParam(name = "url", description = "JDBC URL（PG/CH）；CSV 可空") String url,
            @ToolParam(name = "path", description = "CSV 文件或目录路径；库连接可空") String path,
            @ToolParam(name = "username", description = "用户名（可选）") String username,
            @ToolParam(name = "password", description = "密码（仅当次调用）") String password,
            @ToolParam(name = "schema", description = "schema 过滤（可选）") String schema,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法测通连接");
        }
        if (type == null || type.isBlank()) {
            return emitAndReturn(emitter, "错误：连接类型 type 不能为空");
        }
        try {
            Map<String, Object> probe = buildProbe(type, url, path, username, password, schema, null);
            ConnectionTestResult result = client.testConnection(probe);
            if (!result.ok()) {
                String detail = result.message() == null || result.message().isBlank()
                        ? "连接测试失败"
                        : result.message();
                return emitAndReturn(emitter, detail);
            }
            return emitAndReturn(emitter, "连接测通成功。可继续 listRemoteTables / getRemoteColumns。");
        } catch (Exception e) {
            log.warn("testConnection 失败: type={}", type, e);
            return emitAndReturn(emitter, "连接测试失败：" + e.getMessage());
        }
    }

    @Tool(name = "listRemoteTables", description = "列出远程库中的表名（可按 schema/pattern 过滤）")
    public String listRemoteTables(
            @ToolParam(name = "type", description = "连接类型：postgresql / clickhouse / csv") String type,
            @ToolParam(name = "url", description = "JDBC URL（PG/CH）；CSV 可空") String url,
            @ToolParam(name = "path", description = "CSV 文件或目录路径；库连接可空") String path,
            @ToolParam(name = "username", description = "用户名（可选）") String username,
            @ToolParam(name = "password", description = "密码（仅当次调用）") String password,
            @ToolParam(name = "schema", description = "schema 过滤（可选）") String schema,
            @ToolParam(name = "pattern", description = "表名 pattern 过滤（可选）") String pattern,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法列出远程表");
        }
        if (type == null || type.isBlank()) {
            return emitAndReturn(emitter, "错误：连接类型 type 不能为空");
        }
        try {
            Map<String, Object> probe = buildProbe(type, url, path, username, password, schema, pattern);
            List<String> tables = client.listRemoteTables(probe);
            if (tables.isEmpty()) {
                return emitAndReturn(emitter,
                        "未找到匹配的表。可调整 schema/pattern 后重试；"
                                + "若用户要造库中尚不存在的表，可直接按口述字段建草稿。");
            }
            StringBuilder sb = new StringBuilder("远程表（共 ").append(tables.size()).append(" 张）：\n");
            for (String t : tables) {
                sb.append("- ").append(t).append('\n');
            }
            return emitAndReturn(emitter, sb.toString().trim());
        } catch (Exception e) {
            log.warn("listRemoteTables 失败", e);
            return emitAndReturn(emitter, "列出表失败：" + e.getMessage());
        }
    }

    @Tool(name = "getRemoteColumns", description = "获取指定远程表的字段元数据（名称、类型、可空、主键）")
    public String getRemoteColumns(
            @ToolParam(name = "type", description = "连接类型：postgresql / clickhouse / csv") String type,
            @ToolParam(name = "url", description = "JDBC URL（PG/CH）；CSV 可空") String url,
            @ToolParam(name = "path", description = "CSV 文件或目录路径；库连接可空") String path,
            @ToolParam(name = "username", description = "用户名（可选）") String username,
            @ToolParam(name = "password", description = "密码（仅当次调用）") String password,
            @ToolParam(name = "table", description = "表名") String table,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法获取字段");
        }
        if (type == null || type.isBlank()) {
            return emitAndReturn(emitter, "错误：连接类型 type 不能为空");
        }
        if (table == null || table.isBlank()) {
            return emitAndReturn(emitter, "错误：表名 table 不能为空");
        }
        try {
            Map<String, Object> probe = buildProbe(type, url, path, username, password, null, null);
            String tableName = table.trim();
            List<RemoteColumn> columns = client.listRemoteColumns(probe, tableName);
            if (columns.isEmpty()) {
                return emitAndReturn(emitter, "表「" + tableName + "」无字段或无法读取");
            }
            StringBuilder sb = new StringBuilder("表「").append(tableName).append("」字段（共 ")
                    .append(columns.size()).append(" 个）：\n");
            for (RemoteColumn c : columns) {
                sb.append("- ").append(c.name())
                        .append(" (").append(c.typeName()).append(')');
                if (c.primaryKey()) {
                    sb.append(" PK");
                }
                if (!c.nullable()) {
                    sb.append(" NOT NULL");
                }
                sb.append('\n');
            }
            return emitAndReturn(emitter, sb.toString().trim());
        } catch (Exception e) {
            log.warn("getRemoteColumns 失败: table={}", table, e);
            return emitAndReturn(emitter, "列出字段失败：" + e.getMessage());
        }
    }

    static Map<String, Object> buildProbe(
            String type,
            String url,
            String path,
            String username,
            String password,
            String schema,
            String pattern) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("type", type.trim());
        putIfPresent(probe, "url", url);
        putIfPresent(probe, "path", path);
        putIfPresent(probe, "username", username);
        if (password != null) {
            probe.put("password", password);
        }
        putIfPresent(probe, "schema", schema);
        putIfPresent(probe, "pattern", pattern);
        return probe;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}
