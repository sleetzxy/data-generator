package com.datagenerator.core.constraint;

import com.datagenerator.core.constraint.composite.ConditionalValidator;
import com.datagenerator.core.constraint.field.RangeValidator;
import com.datagenerator.core.expression.SpelExpressionEvaluator;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.core.schema.TableTask;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.DataRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintPipelineTest {

    private YamlConfigLoader yamlLoader;
    private ConstraintLoader constraintLoader;

    @BeforeEach
    void setUp() {
        yamlLoader = new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()));
        constraintLoader = new ConstraintLoader(yamlLoader);
    }

    @Test
    void rangeValidator_rejectsOutOfBounds() {
        var validator = new RangeValidator();
        var result = validator.validate(
                ctxWithField("amount", 999999),
                Map.of("field", "amount", "min", 0.01, "max", 100));
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void conditionalValidator_spelExpression() {
        var evaluator = new SpelExpressionEvaluator();
        var validator = new ConditionalValidator(evaluator);
        Map<String, Object> fields = new HashMap<>();
        fields.put("pay_type", "cash");
        fields.put("bank_account", null);
        var row = new DataRow(fields);
        var result = validator.validate(
                new ConstraintContext(row, Map.of(), Map.of()),
                Map.of(
                        "expression", "pay_type != 'cash' or bank_account == null",
                        "language", "spel"));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void constraintLoader_mergesSchemaJobTableLevels() {
        ConstraintDefinition schemaAmount = fieldRange("amount", 0.01, 99999.99);
        ConstraintDefinition jobCustomerFk = fieldForeignKey("customer_id", "customers", "id");
        ConstraintDefinition tableAmountOverride = fieldRange("amount", 1.0, 100.0);

        List<ConstraintDefinition> merged = ConstraintLoader.merge(
                List.of(schemaAmount),
                List.of(jobCustomerFk),
                List.of(tableAmountOverride));

        assertThat(merged).hasSize(2);

        ConstraintDefinition amount = merged.stream()
                .filter(constraint -> "amount".equals(constraint.getField()))
                .findFirst()
                .orElseThrow();
        assertThat(amount.getMin()).isEqualTo(1.0);
        assertThat(amount.getMax()).isEqualTo(100.0);

        ConstraintDefinition customerFk = merged.stream()
                .filter(constraint -> "customer_id".equals(constraint.getField()))
                .findFirst()
                .orElseThrow();
        assertThat(customerFk.getRefTable()).isEqualTo("customers");
        assertThat(customerFk.getRefField()).isEqualTo("id");
    }

    @Test
    void constraintLoader_loadsFromYamlPaths() {
        SchemaDefinition schema = new SchemaDefinition();
        schema.setConstraints("fixtures/constraints/schema_rules.yaml");

        JobDefinition job = new JobDefinition();
        job.setConstraints("fixtures/constraints/job_rules.yaml");

        TableTask tableTask = new TableTask();
        tableTask.setConstraints("fixtures/constraints/table_rules.yaml");

        List<ConstraintDefinition> merged = constraintLoader.load(schema, job, tableTask);

        assertThat(merged).hasSize(2);

        ConstraintDefinition amount = merged.stream()
                .filter(constraint -> "amount".equals(constraint.getField()))
                .findFirst()
                .orElseThrow();
        assertThat(amount.getMin()).isEqualTo(1.0);
        assertThat(amount.getMax()).isEqualTo(100.0);
    }

    @Test
    void constraintPipeline_runsFieldBeforeComposite() {
        ConstraintDefinition range = fieldRange("amount", 0.01, 10.0);
        ConstraintDefinition conditional = compositeConditional("amount > 0");

        var pipeline = new ConstraintPipeline(List.of(conditional, range));
        var invalidRow = new DataRow(Map.of("amount", 999));
        var result = pipeline.validate(new ConstraintContext(invalidRow, Map.of(), Map.of()));

        assertThat(result.isValid()).isFalse();
        assertThat(result.message()).contains("exceeds maximum");
    }

    private static ConstraintContext ctxWithField(String field, Object value) {
        return new ConstraintContext(new DataRow(Map.of(field, value)), Map.of(), Map.of());
    }

    private static ConstraintDefinition fieldRange(String field, double min, double max) {
        ConstraintDefinition definition = new ConstraintDefinition();
        definition.setLevel("field");
        definition.setField(field);
        definition.setType("range");
        definition.setMin(min);
        definition.setMax(max);
        return definition;
    }

    private static ConstraintDefinition fieldForeignKey(String field, String refTable, String refField) {
        ConstraintDefinition definition = new ConstraintDefinition();
        definition.setLevel("field");
        definition.setField(field);
        definition.setType("foreign_key");
        definition.setRefTable(refTable);
        definition.setRefField(refField);
        return definition;
    }

    private static ConstraintDefinition compositeConditional(String expression) {
        ConstraintDefinition definition = new ConstraintDefinition();
        definition.setLevel("composite");
        definition.setType("conditional");
        definition.setExpression(expression);
        definition.setLanguage("spel");
        return definition;
    }
}
