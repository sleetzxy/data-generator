package com.datagenerator.core.reference;

import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.reader.DataReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LookupReferenceSourceTest {

    @Test
    void loadLookup_cachesValues() {
        DataReader mockReader = new DataReader() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public void init(ReaderConfig config) {
            }

            @Override
            public Stream<DataRow> read(ReadRequest request) {
                return Stream.of(
                        new DataRow(Map.of("code", "BJ")),
                        new DataRow(Map.of("code", "SH"))
                );
            }

            @Override
            public void close() {
            }
        };
        LookupReferenceSource source = new LookupReferenceSource(mockReader);
        List<Object> values = source.load(Map.of("field", "code"));
        assertThat(values).containsExactly("BJ", "SH");
        assertThat(source.load(Map.of("field", "code"))).isSameAs(values);
    }

    @Test
    void referenceDataLoader_cachesBySource() {
        DataReader mockReader = new DataReader() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public void init(ReaderConfig config) {
            }

            @Override
            public Stream<DataRow> read(ReadRequest request) {
                return Stream.of(new DataRow(Map.of("code", "BJ")));
            }

            @Override
            public void close() {
            }
        };
        ReferenceDataLoader loader = new ReferenceDataLoader(Map.of("regions", mockReader));
        List<Object> values = loader.load("regions", Map.of("field", "code"));
        assertThat(values).containsExactly("BJ");
        assertThat(loader.load("regions", Map.of("field", "code"))).isSameAs(values);
    }

    @Test
    void referenceDataLoader_histogramSamples() {
        DataReader mockReader = new DataReader() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public void init(ReaderConfig config) {
            }

            @Override
            public Stream<DataRow> read(ReadRequest request) {
                return Stream.of(
                        new DataRow(Map.of("amount", 10)),
                        new DataRow(Map.of("amount", 20)),
                        new DataRow(Map.of("amount", 30)));
            }

            @Override
            public void close() {
            }
        };
        ReferenceDataLoader loader = new ReferenceDataLoader(Map.of("amounts", mockReader));
        Object sample = loader.sample("amounts", Map.of("field", "amount", "distribution", "histogram"));
        assertThat(((Number) sample).doubleValue()).isBetween(10.0, 30.0);
    }
}
