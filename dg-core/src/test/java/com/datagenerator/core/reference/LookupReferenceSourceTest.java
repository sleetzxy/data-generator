package com.datagenerator.core.reference;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.reader.DataReader;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
    void loadRows_differentInlineUrls_doNotShareRowCache() {
        AtomicReference<String> lastUrl = new AtomicReference<>();
        DataReader mockReader = urlTrackingReader(lastUrl);
        ConnectionRegistry registry = new ConnectionRegistry();
        LookupReferenceSource source = new LookupReferenceSource(mockReader, registry);

        Map<String, Object> configA = lookupConfig(
                "jdbc:postgresql://host-a/db", "SELECT 1");
        Map<String, Object> configB = lookupConfig(
                "jdbc:postgresql://host-b/db", "SELECT 1");

        assertThat(rowsFrom(source, configA)).containsExactly("jdbc:postgresql://host-a/db");
        assertThat(rowsFrom(source, configB)).containsExactly("jdbc:postgresql://host-b/db");
    }

    @Test
    void loadRows_namedConnectionUrlOverride_usesSeparateCache() {
        AtomicReference<String> lastUrl = new AtomicReference<>();
        DataReader mockReader = urlTrackingReader(lastUrl);
        ConnectionRegistry registry = new ConnectionRegistry(Map.of(
                "dev-pg", Map.of(
                        "type", "postgresql",
                        "url", "jdbc:postgresql://registry/db",
                        "username", "registry",
                        "password", "secret")));
        LookupReferenceSource source = new LookupReferenceSource(mockReader, registry);

        Map<String, Object> registryOnly = lookupConfigWithNamedConnection(
                "dev-pg", null, "SELECT 1");
        Map<String, Object> withOverride = lookupConfigWithNamedConnection(
                "dev-pg", "jdbc:postgresql://override/db", "SELECT 1");

        assertThat(rowsFrom(source, registryOnly)).containsExactly("jdbc:postgresql://registry/db");
        assertThat(rowsFrom(source, withOverride)).containsExactly("jdbc:postgresql://override/db");
    }

    @Test
    void loadRows_inlineConnectionMapOverriddenByTopLevelUrl_usesResolvedEndpoint() {
        AtomicReference<String> lastUrl = new AtomicReference<>();
        DataReader mockReader = urlTrackingReader(lastUrl);
        ConnectionRegistry registry = new ConnectionRegistry();
        LookupReferenceSource source = new LookupReferenceSource(mockReader, registry);

        Map<String, Object> inlineOnly = lookupConfigWithInlineConnection(
                "jdbc:postgresql://inline/db", null, "SELECT 1");
        Map<String, Object> withOverride = lookupConfigWithInlineConnection(
                "jdbc:postgresql://inline/db", "jdbc:postgresql://override/db", "SELECT 1");

        assertThat(rowsFrom(source, inlineOnly)).containsExactly("jdbc:postgresql://inline/db");
        assertThat(rowsFrom(source, withOverride)).containsExactly("jdbc:postgresql://override/db");
    }

    @Test
    void loadRows_maxRows_capsMaterializedRows() {
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
                        new DataRow(Map.of("code", "A")),
                        new DataRow(Map.of("code", "B")),
                        new DataRow(Map.of("code", "C")));
            }

            @Override
            public void close() {
            }
        };
        LookupReferenceSource source = new LookupReferenceSource(mockReader);
        Map<String, Object> config = Map.of(
                "reader", Map.of(
                        "type", "mock",
                        "query", "SELECT 1",
                        "maxRows", 2));

        assertThat(source.loadRows(config)).hasSize(2);
        assertThat(source.loadRows(config).stream().map(row -> row.get("code")).toList())
                .containsExactly("A", "B");
    }

    private static List<Object> rowsFrom(LookupReferenceSource source, Map<String, Object> config) {
        return source.loadRows(config).stream()
                .map(row -> row.get("code"))
                .toList();
    }

    private static DataReader urlTrackingReader(AtomicReference<String> lastUrl) {
        return new DataReader() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public void init(ReaderConfig config) {
                lastUrl.set(config.url());
            }

            @Override
            public Stream<DataRow> read(ReadRequest request) {
                return Stream.of(new DataRow(Map.of("code", lastUrl.get())));
            }

            @Override
            public void close() {
            }
        };
    }

    private static Map<String, Object> lookupConfig(String url, String query) {
        return Map.of(
                "field", "code",
                "reader", Map.of(
                        "type", "postgresql",
                        "url", url,
                        "query", query));
    }

    private static Map<String, Object> lookupConfigWithNamedConnection(
            String connectionName, String urlOverride, String query) {
        Map<String, Object> reader = new HashMap<>();
        reader.put("type", "postgresql");
        reader.put("connection", connectionName);
        if (urlOverride != null) {
            reader.put("url", urlOverride);
        }
        reader.put("query", query);
        return Map.of("field", "code", "reader", reader);
    }

    private static Map<String, Object> lookupConfigWithInlineConnection(
            String inlineUrl, String urlOverride, String query) {
        Map<String, Object> reader = new HashMap<>();
        reader.put("type", "postgresql");
        reader.put("connection", Map.of("url", inlineUrl));
        if (urlOverride != null) {
            reader.put("url", urlOverride);
        }
        reader.put("query", query);
        return Map.of("field", "code", "reader", reader);
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
