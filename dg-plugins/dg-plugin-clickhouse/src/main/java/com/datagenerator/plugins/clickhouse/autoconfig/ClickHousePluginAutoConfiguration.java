package com.datagenerator.plugins.clickhouse.autoconfig;

import com.datagenerator.plugins.clickhouse.ClickHouseReader;
import com.datagenerator.plugins.clickhouse.ClickHouseWriter;
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
}
