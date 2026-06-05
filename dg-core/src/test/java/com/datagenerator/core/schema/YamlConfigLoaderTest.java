package com.datagenerator.core.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class YamlConfigLoaderTest {

    private YamlConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()));
    }

    @Test
    void loadSchema_parsesFieldsAndGenerators() {
        SchemaDefinition schema = loader.loadSchema("fixtures/schemas/customer.yaml");
        assertThat(schema.getTable()).isEqualTo("customers");
        assertThat(schema.getFields()).hasSize(3);
        assertThat(schema.getFields().get(0).getGenerator().get("strategy")).isEqualTo("sequence");
    }

    @Test
    void loadJob_parsesDependsOn() {
        JobDefinition job = loader.loadJob("fixtures/jobs/ecommerce_seed.yaml");
        assertThat(job.getJob()).isEqualTo("ecommerce_seed");
        assertThat(job.getConstraints()).contains("constraints/global_rules.yaml");

        TableTask orders = job.findTable("orders").orElseThrow();
        assertThat(orders.getDependsOn()).containsExactly("customers");
        assertThat(orders.getCount()).isEqualTo(5000);
        assertThat(orders.getSchema()).isEqualTo("schemas/order.yaml");
        assertThat(orders.getConstraints()).contains("constraints/order_rules.yaml");

        TableTask orderItems = job.findTable("order_items").orElseThrow();
        assertThat(orderItems.getDependsOn()).containsExactly("orders");
    }

    @Test
    void loadConstraints_parsesRules() {
        ConstraintsDefinition constraints = loader.loadConstraints("fixtures/constraints/order_rules.yaml");
        assertThat(constraints.getConstraints()).hasSize(3);

        ConstraintDefinition rangeRule = constraints.getConstraints().get(0);
        assertThat(rangeRule.getLevel()).isEqualTo("field");
        assertThat(rangeRule.getField()).isEqualTo("amount");
        assertThat(rangeRule.getType()).isEqualTo("range");
        assertThat(rangeRule.getMin()).isEqualTo(0.01);
        assertThat(rangeRule.getMax()).isEqualTo(99999.99);

        ConstraintDefinition fkRule = constraints.getConstraints().get(1);
        assertThat(fkRule.getType()).isEqualTo("foreign_key");
        assertThat(fkRule.getRefTable()).isEqualTo("customers");
        assertThat(fkRule.getRefField()).isEqualTo("id");
    }

    @Test
    void loadReference_parsesReaderConfig() {
        ReferenceDefinition reference = loader.loadReference("region_lookup");
        assertThat(reference.getName()).isEqualTo("region_lookup");
        assertThat(reference.getReader()).containsEntry("type", "postgresql");
        assertThat(reference.getReader()).containsEntry("connection", "dev-pg");
    }

    @Test
    void loadJob_inlineSchemaAndConstraints_parsesTableDefinition() {
        JobDefinition job = loader.loadJob("fixtures/jobs/inline_single.yaml");
        assertThat(job.getJob()).isEqualTo("inline_single");
        assertThat(job.getWriter()).containsEntry("type", "csv");
        assertThat(job.getWriter()).containsEntry("connection", "local-csv");
        assertThat(job.getWriter()).containsEntry("mode", "insert");

        TableTask customers = job.findTable("customers").orElseThrow();
        assertThat(customers.getSchema()).isNull();
        assertThat(customers.getSchemaDefinition()).isNotNull();
        assertThat(customers.getSchemaDefinition().getTable()).isEqualTo("customers");
        assertThat(customers.getSchemaDefinition().getFields()).hasSize(1);
        assertThat(customers.getInlineConstraints()).hasSize(1);
        assertThat(customers.getInlineConstraints().getFirst().getType()).isEqualTo("range");
    }

    @Test
    void overridePath_resolvesTableByName() {
        JobDefinition job = loader.loadJob("fixtures/jobs/ecommerce_seed.yaml");

        Optional<TableTask> customers = job.findTable("customers");
        Optional<TableTask> orders = job.findTable("orders");

        assertThat(customers).isPresent();
        assertThat(orders).isPresent();
        assertThat(OverridePathResolver.resolveTable(job, "tables.customers.count")).isSameAs(customers.get());
        assertThat(OverridePathResolver.resolveTable(job, "tables.orders.count")).isSameAs(orders.get());
        assertThat(OverridePathResolver.resolveField(customers.get(), "tables.customers.count")).isEqualTo("count");
    }
}
