package com.datagenerator.plugins.clickhouse.autoconfig;

import com.datagenerator.plugins.clickhouse.ClickHouseConnectionInspector;
import com.datagenerator.plugins.clickhouse.ClickHouseReader;
import com.datagenerator.plugins.clickhouse.ClickHouseWriter;
import com.datagenerator.spi.inspector.ConnectionInspector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ClickHousePluginAutoConfiguration {

    @Bean
    public ClickHouseReader clickHouseReader() {
        return new ClickHouseReader();
    }

    @Bean
    public ClickHouseWriter clickHouseWriter() {
        return new ClickHouseWriter();
    }

    @Bean
    public ConnectionInspector clickHouseConnectionInspector() {
        return new ClickHouseConnectionInspector();
    }
}
