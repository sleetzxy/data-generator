package com.datagenerator.core.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlConfigLoaderTest {

    private YamlConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()));
    }

    @Test
    void loadSchema_prefixOnNonStringType_throws() {
        assertThatThrownBy(() -> loader.loadSchema("fixtures/schemas/invalid_prefix_type.yaml"))
                .isInstanceOf(ConfigLoadException.class)
                .hasMessageContaining("order_id")
                .hasMessageContaining("VARCHAR, CHAR, TEXT");
    }

    @Test
    void loadSchema_parsesPrimaryKeyFlag() {
        TableSchema schema = loader.loadSchema("fixtures/schemas/pk_customer.yaml");
        assertThat(schema.getFields()).hasSize(1);
        assertThat(schema.getFields().getFirst().isPrimaryKey()).isTrue();
        assertThat(schema.getFields().getFirst().getGenerator()).containsEntry("prefix", "ORD-");
    }

    @Test
    void loadSchema_parsesFieldsAndGenerators() {
        TableSchema schema = loader.loadSchema("fixtures/schemas/customer.yaml");
        assertThat(schema.getTable()).isEqualTo("customers");
        assertThat(schema.getFields()).hasSize(3);
        assertThat(schema.getFields().get(0).getGenerator().get("strategy")).isEqualTo("sequence");
    }

    @Test
    void loadTaskConfig_parsesId() {
        TaskConfig taskConfig = loader.loadTaskConfig("fixtures/task-configs/ecommerce_seed.yaml");
        assertThat(taskConfig.getId()).isEqualTo("ecommerce_seed");
        assertThat(taskConfig.getName()).isEqualTo("电商种子数据造数");
    }

    @Test
    void loadTaskConfig_parsesDependsOn() {
        TaskConfig taskConfig = loader.loadTaskConfig("fixtures/task-configs/ecommerce_seed.yaml");
        assertThat(taskConfig.getName()).isEqualTo("电商种子数据造数");
        assertThat(taskConfig.getConstraints()).contains("constraints/global_rules.yaml");

        TableTask orders = taskConfig.findTable("orders").orElseThrow();
        assertThat(orders.getDependsOn()).containsExactly("customers");
        assertThat(orders.getCount()).isEqualTo(5000);
        assertThat(orders.getSchema()).isEqualTo("schemas/order.yaml");
        assertThat(orders.getConstraints()).contains("constraints/order_rules.yaml");

        TableTask orderItems = taskConfig.findTable("order_items").orElseThrow();
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
    void loadTaskConfig_parsesJobLevelConnections() {
        TaskConfig taskConfig = loader.loadTaskConfig("fixtures/task-configs/task_level_connections.yaml");

        assertThat(taskConfig.getConnections()).containsKey("my-pg");
        assertThat(taskConfig.getConnections().get("my-pg")).containsEntry(
                "url", "jdbc:postgresql://task-host:5432/taskdb");
        assertThat(taskConfig.getWriter()).containsEntry("connection", "my-pg");
    }

    @Test
    void loadTaskConfig_parsesWriters() {
        TaskConfig taskConfig = loader.loadTaskConfig("fixtures/task-configs/multi_write.yaml");
        assertThat(taskConfig.getWriter()).isEmpty();
        assertThat(taskConfig.getWriters()).hasSize(2);
        assertThat(taskConfig.getWriters().get(0)).containsEntry("type", "postgresql");
        assertThat(taskConfig.getWriters().get(1)).containsEntry("type", "clickhouse");
    }

    @Test
    void loadTaskConfig_inlineSchemaAndConstraints_parsesTableDefinition() {
        TaskConfig taskConfig = loader.loadTaskConfig("fixtures/task-configs/inline_single.yaml");
        assertThat(taskConfig.getName()).isEqualTo("内联 Schema 单表造数");
        assertThat(taskConfig.getWriter()).containsEntry("type", "csv");
        assertThat(taskConfig.getWriter()).containsEntry("connection", "local-csv");
        assertThat(taskConfig.getWriter()).containsEntry("mode", "insert");

        TableTask customers = taskConfig.findTable("customers").orElseThrow();
        assertThat(customers.getSchema()).isNull();
        assertThat(customers.getSchemaDefinition()).isNotNull();
        assertThat(customers.getSchemaDefinition().getTable()).isEqualTo("customers");
        assertThat(customers.getSchemaDefinition().getFields()).hasSize(1);
        assertThat(customers.getInlineConstraints()).hasSize(1);
        assertThat(customers.getInlineConstraints().getFirst().getType()).isEqualTo("range");
    }

    @Test
    void loadTaskConfig_withSchedule_parsesEnabledAndCron() {
        TaskConfig taskConfig = loader.loadTaskConfig("fixtures/task-configs/scheduled_task.yaml");
        assertThat(taskConfig.getId()).isEqualTo("scheduled_task");
        assertThat(taskConfig.getName()).isEqualTo("定时任务示例");

        ScheduleDefinition schedule = taskConfig.getSchedule().orElseThrow();
        assertThat(schedule.isEnabled()).isTrue();
        assertThat(schedule.getCron()).isEqualTo("0 0 2 * * ?");
    }

    @Test
    void loadTaskConfigFromContent_withoutIdAndName_accepts() {
        TaskConfig taskConfig = loader.loadTaskConfigFromContent("""
                writer:
                  type: csv
                  path: ./out
                tables:
                  - name: t1
                    count: 1
                    schema:
                      table: t1
                      fields:
                        - name: id
                          type: BIGINT
                          generator: { strategy: sequence, start: 1, step: 1 }
                """);

        assertThat(taskConfig.getId()).isNull();
        assertThat(taskConfig.getName()).isNull();
        assertThat(taskConfig.getTables()).hasSize(1);
    }

    @Test
    void loadTaskConfigFromContent_withIdAndName_keepsThem() {
        TaskConfig taskConfig = loader.loadTaskConfigFromContent("""
                id: my_task
                name: 我的造数任务
                writer:
                  type: csv
                  path: ./out
                tables:
                  - name: t1
                    count: 1
                    schema:
                      table: t1
                      fields:
                        - name: id
                          type: BIGINT
                          generator: { strategy: sequence, start: 1, step: 1 }
                """);

        assertThat(taskConfig.getId()).isEqualTo("my_task");
        assertThat(taskConfig.getName()).isEqualTo("我的造数任务");
    }

    @Test
    void loadTaskConfig_withTaskLevelSeeds_parsesAndValidates() {
        TaskConfig taskConfig = loader.loadTaskConfig("fixtures/task-configs/task_level_seeds.yaml");
        assertThat(taskConfig.getId()).isEqualTo("task_level_seeds");
        assertThat(taskConfig.getSeeds()).hasSize(2);
        assertThat(taskConfig.getSeeds().get(0).getName()).isEqualTo("header");
        assertThat(taskConfig.getSeeds().get(1).getLink().getSeed()).isEqualTo("header");
    }

    @Test
    void loadTaskConfig_unquotedOnKeyword_parsesLinkParentColumn() {
        TaskConfig taskConfig = loader.loadTaskConfig("fixtures/task-configs/seed_link_on_keyword.yaml");

        assertThat(taskConfig.getSeeds().get(1).getLink().resolveParentColumn()).isEqualTo("id");
    }

    @Test
    void overridePath_resolvesTableByName() {
        TaskConfig taskConfig = loader.loadTaskConfig("fixtures/task-configs/ecommerce_seed.yaml");

        Optional<TableTask> customers = taskConfig.findTable("customers");
        Optional<TableTask> orders = taskConfig.findTable("orders");

        assertThat(customers).isPresent();
        assertThat(orders).isPresent();
        assertThat(OverridePathResolver.resolveTable(taskConfig, "tables.customers.count")).isSameAs(customers.get());
        assertThat(OverridePathResolver.resolveTable(taskConfig, "tables.orders.count")).isSameAs(orders.get());
        assertThat(OverridePathResolver.resolveField(customers.get(), "tables.customers.count")).isEqualTo("count");
    }
}
