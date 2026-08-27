package com.datagenerator.core.engine;



import com.datagenerator.core.reference.ReferenceDataLoader;

import com.datagenerator.core.model.FieldDefinition;

import com.datagenerator.core.model.TableSchema;

import com.datagenerator.core.model.SeedDefinition;

import com.datagenerator.core.model.SeedLinkDefinition;

import com.datagenerator.spi.model.DataRow;

import com.datagenerator.spi.model.ReadRequest;

import com.datagenerator.spi.model.ReaderConfig;

import com.datagenerator.spi.reader.DataReader;

import org.junit.jupiter.api.Test;



import java.util.List;

import java.util.Map;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.stream.Stream;



import static org.assertj.core.api.Assertions.assertThat;



class SeedSamplerTest {



    @Test

    void sample_templateSeeds_returnsLinkedValues() {

        SeedDefinition header = templateSeed("header", Map.of("id", 10L, "name", "Alice"));

        SeedDefinition detail = linkedTemplateSeed("detail", "header", Map.of("id", 10L, "sku", "SKU-1"));



        TableSchema schema = schemaWithSeedFields(

                Map.of("id", "header", "name", "header", "sku", "detail"));



        SeedSampler sampler = new SeedSampler(

                new ReferenceDataLoader(Map.of()),

                null,

                SeedDependencySorter.sort(List.of(header, detail)));



        Map<String, DataRow> samples = sampler.sample(schema);



        assertThat(samples).containsKeys("header", "detail");

        assertThat(samples.get("header").get("name")).isEqualTo("Alice");

        assertThat(samples.get("detail").get("sku")).isEqualTo("SKU-1");

    }



    @Test

    void sample_linkedReaderExactlyOneRow_returnsSample() {

        DataReader reader = queryReader(request -> {

            if (request.query().contains("FROM lines")) {

                return Stream.of(new DataRow(Map.of("id", 10L, "line_no", 3)));

            }

            return Stream.of(new DataRow(Map.of("id", 10L, "name", "Bob")));

        });



        SeedDefinition header = readerSeed("header", "SELECT id, name FROM orders LIMIT 1");

        SeedDefinition detail = linkedReaderSeed("detail", "header", "SELECT id, line_no FROM lines");



        TableSchema schema = schemaWithSeedFields(Map.of("name", "header", "line_no", "detail"));

        SeedSampler sampler = new SeedSampler(

                new ReferenceDataLoader(Map.of("postgresql", reader)),

                null,

                SeedDependencySorter.sort(List.of(header, detail)));

        sampler.preloadCaches(schema);



        Map<String, DataRow> samples = sampler.sample(schema);



        assertThat(samples.get("header").get("name")).isEqualTo("Bob");

        assertThat(samples.get("detail").get("line_no")).isEqualTo(3);

    }



    @Test

    void sample_linkedReaderZeroRows_usesEmptySeedAndContinues() {

        DataReader reader = queryReader(request -> {

            if (request.query().contains("FROM lines")) {

                return Stream.empty();

            }

            return Stream.of(new DataRow(Map.of("id", 99L)));

        });



        SeedDefinition header = readerSeed("header", "SELECT id FROM orders LIMIT 1");

        SeedDefinition detail = linkedReaderSeed("detail", "header", "SELECT id FROM lines");



        TableSchema schema = schemaWithSeedFields(Map.of("id", "detail"));

        SeedSampler sampler = new SeedSampler(

                new ReferenceDataLoader(Map.of("postgresql", reader)),

                null,

                SeedDependencySorter.sort(List.of(header, detail)));

        sampler.preloadCaches(schema);



        Map<String, DataRow> samples = sampler.sample(schema);



        assertThat(samples).containsKeys("header", "detail");

        assertThat(samples.get("detail").getFields()).isEmpty();

    }



    @Test

    void sample_linkedReaderMultipleRows_usesIndexedRow() {

        DataReader reader = queryReader(request -> {

            if (request.query().contains("FROM lines")) {

                return Stream.of(

                        new DataRow(Map.of("id", 10L, "line_no", 3)),

                        new DataRow(Map.of("id", 10L, "line_no", 9)));

            }

            return Stream.of(new DataRow(Map.of("id", 10L, "name", "Bob")));

        });



        SeedDefinition header = readerSeed("header", "SELECT id, name FROM orders LIMIT 1");

        SeedDefinition detail = linkedReaderSeed("detail", "header", "SELECT id, line_no FROM lines");



        TableSchema schema = schemaWithSeedFields(Map.of("line_no", "detail"));

        SeedSampler sampler = new SeedSampler(

                new ReferenceDataLoader(Map.of("postgresql", reader)),

                null,

                SeedDependencySorter.sort(List.of(header, detail)));

        sampler.preloadCaches(schema);



        Map<String, DataRow> samples = sampler.sample(schema);



        assertThat(samples.get("detail").get("line_no")).isEqualTo(3);

    }



    @Test

    void sample_sameLinkIdTwice_doesNotReloadLinkedRows() {

        AtomicInteger linkedLoadRowsCalls = new AtomicInteger();

        DataReader reader = queryReader(request -> {

            if (request.query().contains("FROM lines")) {

                return Stream.of(new DataRow(Map.of("id", 10L, "line_no", 3)));

            }

            return Stream.of(new DataRow(Map.of("id", 10L, "name", "Bob")));

        });



        SeedDefinition header = readerSeed("header", "SELECT id, name FROM orders LIMIT 1");

        SeedDefinition detail = linkedReaderSeed(

                "detail", "header", "SELECT id, line_no FROM lines");

        TableSchema schema = schemaWithSeedFields(Map.of("name", "header", "line_no", "detail"));



        ReferenceDataLoader countingLoader = new ReferenceDataLoader(Map.of("postgresql", reader)) {

            @Override

            public List<DataRow> loadRows(String source, Map<String, Object> config) {

                Object readerCfg = config.get("reader");

                if (readerCfg instanceof Map<?, ?> readerMap

                        && String.valueOf(readerMap.get("query")).contains("FROM lines")) {

                    linkedLoadRowsCalls.incrementAndGet();

                }

                return super.loadRows(source, config);

            }

        };



        SeedSampler sampler = new SeedSampler(

                countingLoader,

                null,

                SeedDependencySorter.sort(List.of(header, detail)));

        sampler.preloadCaches(schema);



        assertThat(sampler.sample(schema).get("detail").get("line_no")).isEqualTo(3);

        assertThat(sampler.sample(schema).get("detail").get("line_no")).isEqualTo(3);

        assertThat(linkedLoadRowsCalls.get()).isEqualTo(1);

    }



    @Test

    void sample_rootReaderZeroRows_usesEmptySeed() {

        DataReader reader = queryReader(request -> Stream.empty());

        SeedDefinition header = readerSeed("header", "SELECT id FROM orders LIMIT 1");

        TableSchema schema = schemaWithSeedFields(Map.of("id", "header"));

        SeedSampler sampler = new SeedSampler(

                new ReferenceDataLoader(Map.of("postgresql", reader)),

                null,

                List.of(header));



        Map<String, DataRow> samples = sampler.sample(schema);



        assertThat(samples).containsKey("header");

        assertThat(samples.get("header").getFields()).isEmpty();

    }



    private static SeedDefinition templateSeed(String name, Map<String, Object> template) {

        SeedDefinition seed = new SeedDefinition();

        seed.setName(name);

        seed.setTemplate(template);

        return seed;

    }



    private static SeedDefinition linkedTemplateSeed(String name, String parent, Map<String, Object> template) {

        SeedLinkDefinition link = new SeedLinkDefinition();

        link.setSeed(parent);

        link.setOn("id");

        SeedDefinition seed = templateSeed(name, template);

        seed.setLink(link);

        return seed;

    }



    private static SeedDefinition readerSeed(String name, String query) {

        SeedDefinition seed = new SeedDefinition();

        seed.setName(name);

        seed.setReader(Map.of("type", "postgresql", "query", query));

        return seed;

    }



    private static SeedDefinition linkedReaderSeed(String name, String parent, String query) {

        SeedLinkDefinition link = new SeedLinkDefinition();

        link.setSeed(parent);

        link.setOn("id");

        link.setMatch("equals");

        link.setLocalField("id");

        SeedDefinition seed = readerSeed(name, query);

        seed.setLink(link);

        return seed;

    }



    private static TableSchema schemaWithSeedFields(Map<String, String> fieldSources) {

        TableSchema schema = new TableSchema();

        List<FieldDefinition> fields = fieldSources.entrySet().stream()

                .map(entry -> new FieldDefinition(

                        entry.getKey(),

                        "VARCHAR",

                        Map.of("strategy", "seed", "source", entry.getValue())))

                .toList();

        schema.setFields(fields);

        return schema;

    }



    private interface QueryResponder {

        Stream<DataRow> respond(ReadRequest request);

    }



    private static DataReader queryReader(QueryResponder responder) {

        return new DataReader() {

            @Override

            public String type() {

                return "postgresql";

            }



            @Override

            public void init(ReaderConfig config) {

            }



            @Override

            public Stream<DataRow> read(ReadRequest request) {

                return responder.respond(request);

            }



            @Override

            public void close() {

            }

        };

    }

}

