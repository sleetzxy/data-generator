package com.datagenerator.web.service;

import com.datagenerator.core.engine.PluginRegistry;
import com.datagenerator.spi.inspector.ColumnMeta;
import com.datagenerator.spi.inspector.ConnectionInspector;
import com.datagenerator.spi.inspector.ConnectionProbe;
import com.datagenerator.web.dto.ConnectionProbeRequest;
import com.datagenerator.web.dto.ConnectionTestResponse;
import com.datagenerator.web.dto.RemoteColumnListResponse;
import com.datagenerator.web.dto.RemoteTableListResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 连接探查编排：测通、列库表、列字段。仅调用插件元数据 API，不执行任意用户 SQL。
 */
@Service
public class ConnectionExploreService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionExploreService.class);

    private final PluginRegistry pluginRegistry;

    public ConnectionExploreService(PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    public ConnectionTestResponse test(ConnectionProbeRequest request) {
        ConnectionInspector inspector = resolveInspector(request);
        ConnectionProbe probe = toProbe(request);
        try {
            inspector.test(probe);
            return ConnectionTestResponse.success();
        } catch (Exception exception) {
            throw wrapFailure("连接测试失败", exception);
        }
    }

    public RemoteTableListResponse listTables(ConnectionProbeRequest request) {
        ConnectionInspector inspector = resolveInspector(request);
        ConnectionProbe probe = toProbe(request);
        try {
            List<String> tables =
                    inspector.listTables(probe, blankToNull(request.schema()), blankToNull(request.pattern()));
            return new RemoteTableListResponse(tables);
        } catch (Exception exception) {
            throw wrapFailure("列出表失败", exception);
        }
    }

    public RemoteColumnListResponse listColumns(ConnectionProbeRequest request, String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名 table 不能为空");
        }
        ConnectionInspector inspector = resolveInspector(request);
        ConnectionProbe probe = toProbe(request);
        try {
            List<RemoteColumnListResponse.Column> columns = inspector.listColumns(probe, table.trim()).stream()
                    .map(this::toColumn)
                    .toList();
            return new RemoteColumnListResponse(columns);
        } catch (Exception exception) {
            throw wrapFailure("列出字段失败", exception);
        }
    }

    private ConnectionInspector resolveInspector(ConnectionProbeRequest request) {
        if (request == null || request.type() == null || request.type().isBlank()) {
            throw new IllegalArgumentException("连接类型 type 不能为空");
        }
        String type = request.type().trim();
        try {
            return pluginRegistry.getInspector(type);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的连接类型: " + type);
        }
    }

    private static ConnectionProbe toProbe(ConnectionProbeRequest request) {
        return new ConnectionProbe(
                request.type() == null ? null : request.type().trim(),
                blankToNull(request.url()),
                blankToNull(request.path()),
                blankToNull(request.username()),
                request.password());
    }

    private RemoteColumnListResponse.Column toColumn(ColumnMeta meta) {
        return new RemoteColumnListResponse.Column(
                meta.name(), meta.typeName(), meta.nullable(), meta.primaryKey());
    }

    private IllegalArgumentException wrapFailure(String summary, Exception exception) {
        // 完整异常仅记服务端日志；客户端只返回固定中文摘要，避免泄露 JDBC 主机等细节
        log.warn("连接探查失败: {}", summary, exception);
        return new IllegalArgumentException(summary);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
