package com.datagenerator.plugins.csv.autoconfig;

import com.datagenerator.plugins.csv.CsvReader;
import com.datagenerator.plugins.csv.CsvWriter;
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
}
