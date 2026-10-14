package com.datagenerator.spi.inspector;

import com.datagenerator.spi.Plugin;

import java.util.List;

/**
 * 连接探查插件：测试连通性、列举表与列元数据。
 * {@link #type()} 与 {@link com.datagenerator.spi.reader.DataReader#type()} 对齐，
 * 如 postgresql / clickhouse / csv。
 */
public interface ConnectionInspector extends Plugin {

    @Override
    String type();

    void test(ConnectionProbe probe) throws Exception;

    List<String> listTables(ConnectionProbe probe, String schemaOrNull, String patternOrNull)
            throws Exception;

    List<ColumnMeta> listColumns(ConnectionProbe probe, String table) throws Exception;
}
