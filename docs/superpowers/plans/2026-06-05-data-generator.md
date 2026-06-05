# Data Generator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 P1 阶段的数据生成 REST 服务——5 模块 Maven 工程，支持 PG/CH/CSV 读写、Schema 驱动生成、维表引用、字段级 + SpEL 组合约束、单表/多表 DAG 编排、同步任务执行。

**Architecture:** 模块化单体，4 个 Maven 模块（dg-spi → dg-core → dg-plugins/dg-web）。核心流水线：解析 Job YAML → DAG 拓扑排序 → 逐表生成（Generator → Constraint Pipeline → Writer 批量写入）。插件通过 Spring Boot AutoConfiguration 注册。

**Tech Stack:** Java 21, Spring Boot 3.3, Maven, SnakeYAML, JUnit 5, AssertJ, Testcontainers (PG), JDBC (PG + ClickHouse), Spring SpEL, OpenCSV

**Spec:** `docs/superpowers/specs/2026-06-05-data-generator-design.md`

---

## File Structure（P1 完成后）

```
data-generator/
├── pom.xml                              # 父 POM，dependencyManagement
├── dg-spi/
│   ├── pom.xml
│   └── src/main/java/com/datagenerator/spi/
│       ├── Plugin.java
│       ├── model/DataRow.java, Batch.java, WriteResult.java
│       ├── model/ReaderConfig.java, WriterConfig.java, ReadRequest.java
│       ├── reader/DataReader.java
│       ├── writer/DataWriter.java
│       ├── generator/ValueGenerator.java, GenerationContext.java
│       ├── constraint/ConstraintValidator.java, ConstraintContext.java, ConstraintResult.java
│       └── expression/ExpressionEvaluator.java
├── dg-core/
│   ├── pom.xml
│   └── src/main/java/com/datagenerator/core/
│       ├── schema/                      # YAML 模型 + 解析器
│       │   ├── SchemaDefinition.java, JobDefinition.java, FieldDefinition.java
│       │   ├── ConstraintDefinition.java, ReferenceDefinition.java
│       │   └── YamlConfigLoader.java
│       ├── generator/                   # 内置生成策略
│       │   ├── GeneratorRegistry.java
│       │   ├── RandomGenerator.java, SequenceGenerator.java
│       │   ├── EnumGenerator.java, RegexGenerator.java, ReferenceGenerator.java
│       ├── reference/
│       │   ├── ReferenceDataLoader.java
│       │   └── LookupReferenceSource.java
│       ├── constraint/
│       │   ├── ConstraintPipeline.java, ConstraintLoader.java
│       │   ├── field/RangeValidator.java, NullableValidator.java, ForeignKeyValidator.java
│       │   └── composite/ConditionalValidator.java, MutexValidator.java
│       ├── expression/
│       │   └── SpelExpressionEvaluator.java
│       ├── engine/
│       │   ├── DagSorter.java, JobOrchestrator.java
│       │   ├── TableGenerator.java, GenerationPipeline.java
│       │   └── PluginRegistry.java
│       └── config/
│           └── ConnectionRegistry.java   # 解析 connection 名 → JDBC 参数，注入 Reader/Writer config
│   └── src/test/java/com/datagenerator/core/...
├── dg-plugins/
│   ├── pom.xml
│   └── src/main/java/com/datagenerator/plugins/
│       ├── postgresql/PostgreSqlReader.java, PostgreSqlWriter.java
│       ├── clickhouse/ClickHouseReader.java, ClickHouseWriter.java
│       ├── csv/CsvReader.java, CsvWriter.java
│       └── autoconfig/PluginsAutoConfiguration.java
├── dg-web/
│   ├── pom.xml
│   └── src/main/java/com/datagenerator/web/
│       ├── DataGeneratorApplication.java
│       ├── config/DataGeneratorAutoConfiguration.java    # 装配 core beans + PluginRegistry
│       ├── controller/JobController.java, SchemaController.java, PreviewController.java, HealthController.java
│       ├── dto/JobSubmitRequest.java, JobResponse.java, PreviewRequest.java
│       └── service/JobService.java, SchemaService.java
│   └── src/main/resources/
│       ├── application.yml
│       └── configs/                     # 开发/测试用 YAML fixture
│   └── src/test/java/com/datagenerator/web/...
└── (configs 已迁入 dg-web/src/main/resources/configs/)
    ├── schemas/customer.yaml, order.yaml, order_item.yaml
    ├── references/region_lookup.yaml
    ├── constraints/order_rules.yaml
    └── jobs/single_customer.yaml, ecommerce_seed.yaml
```

---

## Phase Overview

| Phase | 范围 | 本计划 |
|-------|------|--------|
| **P1** | 5 模块骨架、PG/CH/CSV、字段+SpEL 约束、维表引用、DAG、REST 同步 | Task 1–12 ✅ |
| **P2** | 采样分布、异步任务、Aviator、JTS 空间约束 | 附录 A |
| **P3** | 种子模板、Groovy 插件、repair 策略、任务取消 | 附录 B |

---

## Task 1: Maven 多模块骨架

**Files:**
- Create: `pom.xml`, `dg-spi/pom.xml`, `dg-core/pom.xml`, `dg-plugins/pom.xml`, `dg-web/pom.xml`
- Create: `.gitignore`

- [ ] **Step 1: 创建父 POM**

```xml
<!-- pom.xml -->
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.datagenerator</groupId>
  <artifactId>data-generator</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <modules>
    <module>dg-spi</module>
    <module>dg-core</module>
    <module>dg-plugins</module>
    <module>dg-web</module>
  </modules>
  <properties>
    <java.version>21</java.version>
    <spring-boot.version>3.3.5</spring-boot.version>
    <maven.compiler.release>21</maven.compiler.release>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.datagenerator</groupId>
        <artifactId>dg-spi</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.datagenerator</groupId>
        <artifactId>dg-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.datagenerator</groupId>
        <artifactId>dg-plugins</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.datagenerator</groupId>
        <artifactId>dg-web</artifactId>
        <version>${project.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

- [ ] **Step 2: 创建各子模块 pom.xml**

`dg-spi/pom.xml` — 无 Spring 依赖，仅 JUnit：
```xml
<artifactId>dg-spi</artifactId>
<dependencies>
  <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
</dependencies>
```

`dg-core/pom.xml` — 依赖 dg-spi + snakeyaml + spring-expression + junit + assertj：
```xml
<dependencies>
  <dependency><groupId>com.datagenerator</groupId><artifactId>dg-spi</artifactId></dependency>
  <dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId><version>2.2</version></dependency>
  <dependency><groupId>org.springframework</groupId><artifactId>spring-expression</artifactId></dependency>
  <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
</dependencies>
```

`dg-plugins/pom.xml` — 依赖 dg-spi + postgresql driver + clickhouse-jdbc + opencsv + spring-boot-autoconfigure
`dg-web/pom.xml` — 依赖 dg-core + dg-plugins + spring-boot-starter-web + spring-boot-starter-test，配置 spring-boot-maven-plugin

- [ ] **Step 3: 创建 .gitignore**

```
target/
.idea/
*.iml
.env
```

- [ ] **Step 4: 验证构建**

Run: `mvn -f pom.xml clean compile -q`
Expected: BUILD SUCCESS（空模块）

- [ ] **Step 5: Commit**

```bash
git init
git add pom.xml dg-*/pom.xml .gitignore
git commit -m "chore: scaffold Maven multi-module project"
```

---

## Task 2: dg-spi 插件契约与公共模型

**Files:**
- Create: `dg-spi/src/main/java/com/datagenerator/spi/**/*.java`
- Test: `dg-spi/src/test/java/com/datagenerator/spi/model/DataRowTest.java`

- [ ] **Step 1: 写 failing test — DataRow**

```java
// dg-spi/src/test/java/com/datagenerator/spi/model/DataRowTest.java
package com.datagenerator.spi.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataRowTest {
    @Test
    void getAndSetField() {
        DataRow row = new DataRow();
        row.set("name", "Alice");
        assertEquals("Alice", row.get("name"));
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `mvn -pl dg-spi test -Dtest=DataRowTest -q`
Expected: compilation error — DataRow not found

- [ ] **Step 3: 实现 SPI 接口与模型**

核心文件：

```java
// Plugin.java
public interface Plugin { String type(); }

// DataRow.java — Map<String, Object> 包装，含 get/set/getFields
// Batch.java — List<DataRow> + tableName
// WriteResult.java — writtenCount, failedCount
// ReaderConfig.java — type, connection, query, path 等
// WriterConfig.java — type, connection, mode (insert)
// ReadRequest.java — query overrides
// GenerationContext.java — 当前行、表名、上游表数据 Map<String, List<DataRow>>
// ConstraintContext.java — 当前 DataRow + 上游数据 + bindings
// ConstraintResult.java — valid, message, repairedValue
// DataReader.java, DataWriter.java, ValueGenerator.java
// ConstraintValidator.java, ExpressionEvaluator.java
```

`ValueGenerator` 接口：
```java
public interface ValueGenerator extends Plugin {
    String strategy();  // "random"|"sequence"|"enum"|"regex"|"reference"
    Object generate(GenerationContext ctx, Map<String, Object> config);
}
```

- [ ] **Step 4: Run test — expect PASS**

Run: `mvn -pl dg-spi test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add dg-spi/
git commit -m "feat(spi): add plugin interfaces and core models"
```

---

## Task 3: YAML Schema 解析

**Files:**
- Create: `dg-core/src/main/java/com/datagenerator/core/schema/*.java`
- Test: `dg-core/src/test/java/com/datagenerator/core/schema/YamlConfigLoaderTest.java`
- Test fixture: `dg-core/src/test/resources/fixtures/schemas/customer.yaml`

- [ ] **Step 1: 写 failing test**

```java
@Test
void loadSchema_parsesFieldsAndGenerators() {
    SchemaDefinition schema = loader.loadSchema("fixtures/schemas/customer.yaml");
    assertThat(schema.getTable()).isEqualTo("customers");
    assertThat(schema.getFields()).hasSize(3);
    assertThat(schema.getFields().get(0).getGenerator().get("strategy")).isEqualTo("sequence");
}
```

Fixture `customer.yaml`:
```yaml
table: customers
fields:
  - name: id
    type: BIGINT
    generator: { strategy: sequence, start: 1, step: 1 }
  - name: name
    type: VARCHAR
    generator: { strategy: random, type: string, length: 10 }
  - name: region_code
    type: VARCHAR
    generator:
      strategy: reference
      source: region_lookup
      reader: { type: postgresql, connection: dev-pg, query: "SELECT code FROM regions" }
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `mvn -pl dg-core test -Dtest=YamlConfigLoaderTest -q`

- [ ] **Step 3: 实现解析器**

```java
// SchemaDefinition — table, constraints(path), fields
// FieldDefinition — name, type, generator(Map)
// JobDefinition — job, constraints, tables(List<TableTask>)
// TableTask — name, schema(path), count, dependsOn, constraints
// ConstraintDefinition — level, field, type, expression, language, min, max, ...
// YamlConfigLoader — loadSchema(path), loadJob(path), loadConstraints(path), loadReference(name)
//   使用 SnakeYAML + 自定义 Constructor 或 Map 转 POJO
// ConfigPathResolver — 相对 config-dir 解析路径
```

额外测试：加载 Job YAML，验证 `depends_on` 和 `overrides` 路径解析。

- [ ] **Step 4: Run test — expect PASS**

Run: `mvn -pl dg-core test -Dtest=YamlConfigLoaderTest -q`

- [ ] **Step 5: Commit**

```bash
git add dg-core/src/main/java/com/datagenerator/core/schema/ dg-core/src/test/
git commit -m "feat(core): add YAML schema and job config loader"
```

---

## Task 4: 内置生成策略

**Files:**
- Create: `dg-core/src/main/java/com/datagenerator/core/generator/*.java`
- Test: `dg-core/src/test/java/com/datagenerator/core/generator/GeneratorsTest.java`

- [ ] **Step 1: 写 failing tests（每个策略一个 test method）**

```java
@Test void sequence_increments() {
    var gen = new SequenceGenerator();
    var ctx = new GenerationContext();
    assertThat(gen.generate(ctx, Map.of("start", 100, "step", 1))).isEqualTo(100L);
    assertThat(gen.generate(ctx, Map.of("start", 100, "step", 1))).isEqualTo(101L);
}

@Test void enum_picksFromList() {
    var gen = new EnumGenerator();
    Object val = gen.generate(new GenerationContext(), Map.of("values", List.of("A", "B")));
    assertThat(List.of("A", "B")).contains(val);
}

@Test void regex_generatesMatchingString() {
    var gen = new RegexGenerator();
    String val = (String) gen.generate(new GenerationContext(), Map.of("pattern", "[0-9]{4}"));
    assertThat(val).matches("[0-9]{4}");
}
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `mvn -pl dg-core test -Dtest=GeneratorsTest -q`

- [ ] **Step 3: 实现生成器 + GeneratorRegistry**

```java
// RandomGenerator — 支持 int/long/double/string/date 子类型
// SequenceGenerator — AtomicLong 或 context 级计数器
// EnumGenerator — Random 从 values 列表选取
// RegexGenerator — 使用 Generex 库或简化实现（P1 可用预定义模式表）
// ReferenceGenerator — 委托 ReferenceDataLoader（Task 5 实现）
// GeneratorRegistry — Map<strategy, ValueGenerator>，get(strategy)
```

`ReferenceGenerator` P1 仅支持 `distribution: uniform` 从缓存列表随机选取。

- [ ] **Step 4: Run test — expect PASS**

Run: `mvn -pl dg-core test -Dtest=GeneratorsTest -q`

- [ ] **Step 5: Commit**

```bash
git add dg-core/src/main/java/com/datagenerator/core/generator/
git commit -m "feat(core): add built-in value generators"
```

---

## Task 5: 维表参考数据加载

**Files:**
- Create: `dg-core/src/main/java/com/datagenerator/core/reference/*.java`
- Test: `dg-core/src/test/java/com/datagenerator/core/reference/LookupReferenceSourceTest.java`

- [ ] **Step 1: 写 failing test — 内存 Reader mock**

```java
@Test
void loadLookup_cachesValues() {
    DataReader mockReader = request -> Stream.of(
        new DataRow(Map.of("code", "BJ")),
        new DataRow(Map.of("code", "SH"))
    );
    LookupReferenceSource source = new LookupReferenceSource(mockReader);
    List<Object> values = source.load(Map.of("field", "code"));
    assertThat(values).containsExactly("BJ", "SH");
    // 第二次调用走缓存
    assertThat(source.load(Map.of("field", "code"))).isSameAs(values);
}
```

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: 实现 ReferenceDataLoader + LookupReferenceSource**

```java
// ReferenceDataLoader — 根据 reader config 从 PluginRegistry 获取 DataReader，读取并缓存
// LookupReferenceSource — 按 field 名提取列值列表，支持 uniform 随机选取
// 支持 configs/references/*.yaml 独立引用（ReferenceDefinition 模型）
```

P1 不支持采样分布（P2），遇到 `distribution: histogram` 抛 UnsupportedOperationException。

- [ ] **Step 4: Run test — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(core): add lookup reference data loader"
```

---

## Task 6: 约束引擎（字段级 + SpEL 组合）

**Files:**
- Create: `dg-core/src/main/java/com/datagenerator/core/constraint/**/*.java`
- Create: `dg-core/src/main/java/com/datagenerator/core/expression/SpelExpressionEvaluator.java`
- Test: `dg-core/src/test/java/com/datagenerator/core/constraint/ConstraintPipelineTest.java`

- [ ] **Step 1: 写 failing tests**

```java
@Test void rangeValidator_rejectsOutOfBounds() {
    var v = new RangeValidator();
    var result = v.validate(ctxWithField("amount", 999999), Map.of("field", "amount", "min", 0.01, "max", 100));
    assertThat(result.isValid()).isFalse();
}

@Test void conditionalValidator_spelExpression() {
    var eval = new SpelExpressionEvaluator();
    var v = new ConditionalValidator(eval);
    var row = new DataRow(Map.of("pay_type", "cash", "bank_account", null));
    var result = v.validate(new ConstraintContext(row, Map.of()), Map.of(
        "expression", "pay_type != 'cash' or bank_account == null", "language", "spel"));
    assertThat(result.isValid()).isTrue();
}

@Test void constraintLoader_mergesSchemaJobTableLevels() {
    // Schema 级 + Job 级 + 表项级叠加，表项级覆盖同 field 约束
}
```

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: 实现约束管道**

```java
// ConstraintPipeline — 按 level 分阶段执行：field → composite
// ConstraintLoader — Schema→Job→Table 三级加载合并（spec §6.2）
P1 字段级约束：`range`、`nullable`、`foreign_key`（规格示例对齐）；`type`/`enum`/`regex` 字段约束可后续补全。
// ConditionalValidator — 委托 ExpressionEvaluator
// MutexValidator — at_least_one 规则
// SpelExpressionEvaluator — StandardEvaluationContext + row bindings
```

P1 `on_fail` 仅支持 `reject`，`repair`/`warn` 留 P3。

- [ ] **Step 4: Run test — expect PASS**

Run: `mvn -pl dg-core test -Dtest=ConstraintPipelineTest -q`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(core): add constraint pipeline with field and SpEL composite validators"
```

---

## Task 7: DAG 编排与生成流水线

**Files:**
- Create: `dg-core/src/main/java/com/datagenerator/core/engine/*.java`
- Test: `dg-core/src/test/java/com/datagenerator/core/engine/DagSorterTest.java`
- Test: `dg-core/src/test/java/com/datagenerator/core/engine/TableGeneratorTest.java`

- [ ] **Step 1: 写 failing tests**

```java
@Test void dagSorter_topologicalOrder() {
    List<TableTask> tables = List.of(
        task("orders", List.of("customers")),
        task("customers", List.of()),
        task("order_items", List.of("orders"))
    );
    assertThat(DagSorter.sort(tables).stream().map(TableTask::getName))
        .containsExactly("customers", "orders", "order_items");
}

@Test void dagSorter_detectsCycle() {
    assertThatThrownBy(() -> DagSorter.sort(List.of(
        task("a", List.of("b")), task("b", List.of("a"))
    ))).isInstanceOf(IllegalArgumentException.class);
}

@Test void tableGenerator_producesRowsWithConstraints() {
    // 用 mock Writer 验证生成 count 行，外键引用上游表
}
```

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: 实现引擎**

```java
// DagSorter — Kahn 算法拓扑排序 + 环检测
// PluginRegistry — 注册 DataReader/DataWriter/ValueGenerator/ConstraintValidator
// TableGenerator — 对单表：逐行 generate fields → field constraints → row constraints
//   约束失败时按 maxRetries 重试（reject 策略），超限则跳过该行并计入 failedRows
// GenerationPipeline — 批量收集 → Writer.write(batch)，默认 batchSize=1000
// JobOrchestrator — sort DAG → 逐表 TableGenerator → 汇总 JobResult（含 failedRows）
// ConnectionRegistry — 在 dg-core 层解析 connection 名 → JDBC URL/凭证
//   将已解析参数写入 ReaderConfig/WriterConfig 后再调用 plugin.init()；插件不依赖 dg-core
```

`GenerationContext` 注入上游表已生成的 ID 列表，供 `ReferenceGenerator` 的 `source: customers` 使用。

- [ ] **Step 4: Run test — expect PASS**

Run: `mvn -pl dg-core test -q`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(core): add DAG orchestrator and generation pipeline"
```

---

## Task 8: PostgreSQL 插件

**Files:**
- Create: `dg-plugins/src/main/java/com/datagenerator/plugins/postgresql/*.java`
- Test: `dg-plugins/src/test/java/com/datagenerator/plugins/postgresql/PostgreSqlIntegrationTest.java`

- [ ] **Step 1: 写 failing integration test（Testcontainers）**

```java
@Testcontainers
class PostgreSqlIntegrationTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void writer_insertsBatch() { /* 建表 → write 3 rows → 查询验证 */ }

    @Test void reader_readsQuery() { /* 插入数据 → read → 验证 DataRow */ }
}
```

`dg-plugins/pom.xml` 添加 testcontainers postgresql 依赖（test scope）。

- [ ] **Step 2: Run test — expect FAIL**

Run: `mvn -pl dg-plugins test -Dtest=PostgreSqlIntegrationTest -q`

- [ ] **Step 3: 实现 PostgreSqlReader + PostgreSqlWriter**

```java
// PostgreSqlReader — JDBC 执行 query，ResultSet → DataRow
// PostgreSqlWriter — JDBC batch insert，支持 mode: insert
// 插件只消费 ConnectionRegistry 已解析并注入的 ReaderConfig/WriterConfig（url/user/password），不引用 dg-core
```

- [ ] **Step 4: Run test — expect PASS**（需 Docker）

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(plugins): add PostgreSQL reader and writer"
```

---

## Task 9: ClickHouse 与 CSV 插件

**Files:**
- Create: `dg-plugins/src/main/java/com/datagenerator/plugins/clickhouse/*.java`
- Create: `dg-plugins/src/main/java/com/datagenerator/plugins/csv/*.java`
- Create: `dg-plugins/src/main/java/com/datagenerator/plugins/autoconfig/PluginsAutoConfiguration.java`
- Test: `dg-plugins/src/test/java/com/datagenerator/plugins/csv/CsvReaderWriterTest.java`

- [ ] **Step 1: 写 failing test — CSV 读写**

```java
@Test void csvWriterAndReader_roundTrip() throws IOException {
    Path file = Files.createTempFile("test", ".csv");
    // write 3 rows → read back → assert count and values
}
```

ClickHouse 测试可用 Testcontainers `clickhouse/clickhouse-server` 或 `@Disabled` 标记需 Docker 的集成测试。

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: 实现插件 + AutoConfiguration**

```java
// ClickHouseReader/Writer — clickhouse-jdbc，P1 仅维表 SELECT + INSERT
// CsvReader — OpenCSV，支持 path + header
// CsvWriter — OpenCSV，输出到 path
// PluginsAutoConfiguration — 仅声明 @Bean PostgreSqlReader, ClickHouseReader, CsvReader 等
//   不在此模块引用 PluginRegistry（dg-plugins 仅依赖 dg-spi）
// DataGeneratorAutoConfiguration（dg-web）— 注入 List<DataReader>/List<DataWriter> 并注册到 PluginRegistry
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.datagenerator.plugins.autoconfig.PluginsAutoConfiguration
```

- [ ] **Step 4: Run test — expect PASS**

Run: `mvn -pl dg-plugins test -q`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(plugins): add ClickHouse and CSV plugins with auto-configuration"
```

---

## Task 10: REST API 层

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/**/*.java`
- Test: `dg-web/src/test/java/com/datagenerator/web/controller/JobControllerTest.java`（切片测试）

- [ ] **Step 1: 写 failing MockMvc 切片 test**

使用 `@WebMvcTest(JobController.class)` + `@MockBean JobService`，不启动完整 Spring 上下文：

```java
@WebMvcTest(JobController.class)
class JobControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean JobService jobService;

    @Test void submitJob_delegatesToService() throws Exception {
        when(jobService.submit(any())).thenReturn(JobResponse.completed("job-1", 100));
        mockMvc.perform(post("/api/v1/jobs")
            .contentType(APPLICATION_JSON)
            .content("""
                {"jobConfig":"jobs/single_customer.yaml",
                 "writer":{"type":"csv","connection":"local-csv","mode":"insert"}}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
```

全栈 API 测试放在 Task 11 `dg-web` 的 `EndToEndTest` 中。

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: 实现 API**

```java
// JobSubmitRequest — jobConfig, overrides, writer, options
// JobResponse — jobId, status, progress, details, duration
// PreviewRequest — 继承 JobSubmitRequest + preview(limit, tables)
// JobService — 调用 JobOrchestrator，P1 仅同步模式（忽略 asyncThreshold 大于实际量即可）
// JobController — POST /api/v1/jobs, GET /api/v1/jobs/{id}（P1 同步模式：内存 Map 存近期 job，供查询）
// PreviewController — POST /api/v1/preview
// SchemaController — GET /api/v1/schemas, GET /api/v1/schemas/{name}
// HealthController — GET /api/v1/health → { "status": "UP" }
// GlobalExceptionHandler — 400/500 统一错误格式
```

P1 异步路径：`syncThreshold` 默认 5000，所有 P1 测试数据量小，始终同步返回 200。

- [ ] **Step 4: Run test — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(api): add REST endpoints for jobs, preview, and schemas"
```

---

## Task 11: Spring Boot 启动模块

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/DataGeneratorApplication.java`
- Create: `dg-web/src/main/resources/application.yml`
- Create: `dg-web/src/main/resources/configs/**` (sample YAML fixtures)
- Test: `dg-web/src/test/java/com/datagenerator/web/EndToEndTest.java`

- [ ] **Step 1: 写 failing 端到端 test**

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class EndToEndTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void fullJob_generatesAndWrites() {
        // 1. 在 PG 建 regions 维表并插入数据
        // 2. POST /api/v1/jobs with ecommerce_seed.yaml, count 小量
        // 3. 验证 customers/orders 表有数据且外键有效
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: 实现 dg-web**

```java
// DataGeneratorApplication — @SpringBootApplication
// DataGeneratorAutoConfiguration — @Bean JobOrchestrator, YamlConfigLoader, ConnectionRegistry, PluginRegistry
//   注入 List<DataReader>/List<DataWriter>（来自 PluginsAutoConfiguration）并注册到 PluginRegistry
// application.yml:
data-generator:
  config-dir: ./configs
  connections:
    dev-pg:
      type: postgresql
      url: jdbc:postgresql://localhost:5432/dev
      username: test
      password: test
  job:
    sync-threshold: 5000
    batch-size: 1000
```

创建 sample configs：
- `schemas/customer.yaml`, `order.yaml`, `order_item.yaml`
- `references/region_lookup.yaml`
- `constraints/order_rules.yaml`
- `jobs/single_customer.yaml`（单表）
- `jobs/ecommerce_seed.yaml`（三表 DAG：customers → orders → order_items，对齐 spec §5.4）

- [ ] **Step 4: Run test — expect PASS**

Run: `mvn -pl dg-web test -Dtest=EndToEndTest -q`

- [ ] **Step 5: Commit**

```bash
git add dg-web/
git commit -m "feat(web): add Spring Boot entrypoint with sample configs and e2e test"
```

---

## Task 12: P1 验收与文档

**Files:**
- Create: `README.md`

- [ ] **Step 1: 全量测试**

Run: `mvn clean test`
Expected: BUILD SUCCESS

- [ ] **Step 2: 打包可执行 JAR**

Run: `mvn -pl dg-web package -DskipTests`
Expected: `dg-web/target/dg-web-0.1.0-SNAPSHOT.jar`

- [ ] **Step 3: 手动冒烟测试**

Run:
```bash
java -jar dg-web/target/dg-web-0.1.0-SNAPSHOT.jar
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/api/v1/schemas
curl -X POST http://localhost:8080/api/v1/preview \
  -H "Content-Type: application/json" \
  -d '{"jobConfig":"jobs/single_customer.yaml","preview":{"limit":5}}'
```

- [ ] **Step 4: 编写 README**

包含：项目简介、模块结构、快速启动、配置目录说明、API 示例、P1 能力边界。

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: add README with quickstart and P1 capability summary"
```

---

## 附录 A: P2 任务概要

| Task | 内容 |
|------|------|
| P2-1 | `HistogramDistribution` + `NormalDistribution` 采样生成 |
| P2-2 | `AsyncJobExecutor` + 内存 JobStore + 202/轮询 API |
| P2-3 | `AviatorExpressionEvaluator` + custom 约束级 |
| P2-4 | `SpatialValidators`（JTS 可选依赖）+ within/contains 拓扑 |

## 附录 B: P3 任务概要

| Task | 内容 |
|------|------|
| P3-1 | `SeedTemplateGenerator` 种子变异 |
| P3-2 | `GroovyExpressionEvaluator` + 脚本插件 SPI |
| P3-3 | 约束 `repair`/`warn` 策略 |
| P3-4 | `DELETE /jobs/{id}` 任务取消 |

---

## 依赖版本参考

| 依赖 | 版本 |
|------|------|
| Spring Boot | 3.3.5 |
| snakeyaml | 2.2 |
| clickhouse-jdbc | 0.6.3 |
| postgresql | 42.7.3 |
| opencsv | 5.9 |
| testcontainers | 1.20.3 |
| generex | 1.0.2（regex 生成，可选） |
