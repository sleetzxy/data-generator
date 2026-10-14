package com.datagenerator.plugins.csv.autoconfig;

import com.datagenerator.plugins.csv.CsvConnectionInspector;
import com.datagenerator.plugins.csv.CsvReader;
import com.datagenerator.plugins.csv.CsvWriter;
import com.datagenerator.spi.inspector.ConnectionInspector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CsvPluginAutoConfiguration {

    @Bean
    public CsvReader csvReader() {
        return new CsvReader();
    }

    @Bean
    public CsvWriter csvWriter() {
        return new CsvWriter();
    }

    @Bean
    public ConnectionInspector csvConnectionInspector() {
        return new CsvConnectionInspector();
    }
}
