# Data Generator 设计规格

**日期：** 2026-06-05  
**状态：** 已批准  
**版本：** 1.0

---

## 1. 概述

### 1.1 目标

构建一个基于参考数据、符合业务规则的测试数据自动生成服务。支持多异构数据源批量读写，通过 YAML 配置定义 Schema、生成策略与约束规则，以 REST API 对外提供造数能力。

### 1.2 使用场景

| 场景 | 说明 |
|------|------|
| **A. 自动化测试造数** | 单元/集成/E2E 测试前批量生成符合业务规则的测试数据 |
| **B. 开发/联调环境填充** | 替代手工 SQL/Excel，快速填充开发或联调环境 |

### 1.3 核心能力

- 插件化 Reader/Writer 架构，支持关系库、CSV 等异构数据源（XLSX 为远期扩展）
- 基于 Schema 的智能生成器：随机、序列、枚举、正则、参考数据分布
- 多层约束引擎：字段级、组合级、空间级、自定义表达式
- 四大扩展点：数据源、生成策略、约束类型、表达式语言
- 单表快捷模式 + 多表 DAG 编排
- 混合同步/异步任务执行

### 1.4 技术栈

- Java 21
- Spring Boot（REST 服务）
- SpEL / Aviator / Groovy（表达式引擎）
- JTS（可选，空间约束）
- YAML 配置

---

## 2. 架构决策

### 2.1 方案选择

采用**模块化单体**（方案一）：Spring Boot 单应用部署，内部分为 5 个 Maven 模块。核心引擎与 REST 层包级隔离，后续可无痛拆出为独立库或 Worker。

未采用 API + Worker 分离方案，原因是 MVP 阶段部署与运维复杂度不必要；未采用纯嵌入式引擎方案，原因是当前交付形态为 REST 独立服务。

### 2.2 关键决策汇总

| 维度 | 决策 |
|------|------|
| 交付形态 | REST 独立服务 |
| 执行模式 | 混合：小任务同步（≤ 阈值），大批量异步 |
| 配置管理 | 本地目录存放 YAML，服务启动时加载 |
| MVP 数据源 | PostgreSQL、ClickHouse、CSV |
| 参考数据 | 维表引用 / 采样分布 / 种子模板（分阶段） |
| 约束引擎 | 字段 + 组合 + 空间 + 自定义表达式（分模块启用） |
| 多表支持 | 单表快捷 + 多表 DAG 编排 |

---

## 3. 模块结构

```
data-generator/                    # 父 POM
│
├── dg-spi/                        # 插件契约（四大扩展点接口 + 公共模型）
├── dg-core/                       # 核心引擎（全部业务逻辑）
├── dg-plugins/                    # 数据源插件（PG / CH / CSV）
├── dg-api/                        # REST 层
└── dg-app/                        # 启动装配（Spring Boot 入口）
```

### 3.1 模块职责

| 模块 | 职责 |
|------|------|
| **dg-spi** | Reader/Writer、Generator、Constraint、Expression 接口；DataRow、Plugin 等公共模型 |
| **dg-core** | YAML 解析、参考数据、生成策略、约束引擎、DAG 编排、任务执行 |
| **dg-plugins** | PostgreSQL、ClickHouse、CSV 的 Reader/Writer 实现 |
| **dg-api** | Controller、DTO、任务状态查询、同步/异步调度入口 |
| **dg-app** | 依赖聚合、Spring Boot 启动、本地配置目录加载 |

### 3.2 模块依赖

```
dg-app
 ├── dg-api
 ├── dg-core
 └── dg-plugins

dg-api → dg-core → dg-spi
dg-plugins → dg-spi
```

### 3.3 模块内包划分

**dg-core：**

```
com.example.dg.core
├── schema/          # YAML 解析与校验
├── reference/       # 参考数据（维表/采样/种子）
├── generator/       # 生成策略
├── constraint/      # 约束引擎（field / composite / spatial / expression）
└── engine/          # DAG 编排、流水线、批处理
```

**dg-plugins：**

```
com.example.dg.plugins
├── postgresql/
├── clickhouse/
└── csv/
```

### 3.4 演进策略

- 单个插件需独立发布时，从 `dg-plugins` 拆出（如 `dg-plugin-oracle`）
- 核心引擎需嵌入其他项目时，从 `dg-core` + `dg-spi` 打包为独立库
- 大批量场景需水平扩展时，将 `dg-core/engine` 的任务执行部分拆为独立 Worker 进程

---

## 4. 插件 SPI 与四大扩展点

### 4.1 数据源扩展（Reader / Writer）

```java
public interface DataReader extends Plugin {
    String type();                          // "postgresql", "clickhouse", "csv"
    void init(ReaderConfig config);
    Stream<DataRow> read(ReadRequest request);
    void close();
}

public interface DataWriter extends Plugin {
    String type();
    void init(WriterConfig config);
    WriteResult write(Batch<DataRow> batch);
    void flush();
    void close();
}
```

- **Reader**：读取参考数据或已有数据采样
- **Writer**：批量写入生成结果
- 批量粒度由 `dg-core/engine` 统一控制，默认 1000 行/批

### 4.2 生成策略扩展（Generator）

```java
public interface ValueGenerator extends Plugin {
    String strategy();                      // "random", "sequence", "enum", "regex", "reference"
    Object generate(GenerationContext ctx);
}
```

内置策略在 `dg-core/generator` 包实现；自定义策略实现 SPI 后注册即可。

### 4.3 约束类型扩展（Constraint）

```java
public interface ConstraintValidator extends Plugin {
    String type();                          // "range", "foreign_key", "mutex", "spatial", ...
    ConstraintResult validate(ConstraintContext ctx);
    default Object repair(ConstraintContext ctx, Object value) { return value; }
}
```

约束按阶段组成管道（Pipeline），校验失败时可选择**拒绝**或**自动修正**（由 YAML 配置决定）。

### 4.4 表达式语言扩展（ExpressionEvaluator）

```java
public interface ExpressionEvaluator extends Plugin {
    String language();                      // "spel", "aviator", "groovy"
    Object evaluate(String expression, Map<String, Object> bindings);
}
```

自定义约束、条件生成、组合规则统一走此接口。

### 4.5 插件发现机制

| 方式 | 说明 |
|------|------|
| Java `ServiceLoader` | 核心引擎零 Spring 依赖时使用 |
| Spring Boot AutoConfiguration | 推荐，通过 `AutoConfiguration.imports` 注册 |
| `dg-app` 显式 `@Import` | MVP 阶段最可控，插件清单写在 `application.yml` |

MVP 采用：**显式配置 + AutoConfiguration 双轨**。

---

## 5. Schema 与生成策略

### 5.1 字段生成配置

```yaml
# configs/schemas/order.yaml
table: orders
constraints: constraints/order_rules.yaml   # 引用可复用约束集（可选）
fields:
  - name: order_id
    type: BIGINT
    generator:
      strategy: sequence
      start: 100000
      step: 1

  - name: customer_id
    type: BIGINT
    generator:
      strategy: reference
      source: customers          # 引用同任务已生成表
      distribution: uniform

  - name: region_code
    type: VARCHAR
    generator:
      strategy: reference
      source: region_lookup      # 维表引用
      reader:
        type: postgresql
        connection: dev-pg       # 引用 application.yml 中的连接名
        query: "SELECT code FROM regions"

  - name: amount
    type: DECIMAL
    generator:
      strategy: reference
      source: amount_sample      # 采样分布 [P2]
      reader:
        type: clickhouse
        connection: dev-ch
        query: "SELECT amount FROM prod.orders SAMPLE 10000"
      distribution:
        type: histogram          # 或 normal, uniform, custom
```

**Reader 连接解析：** Reader 与 Writer 统一通过 `connection` 字段引用 `application.yml` 中 `data-generator.connections` 定义的连接名，不在 Schema YAML 中硬编码凭证。

**参考数据配置方式：**

| 方式 | 适用场景 | 示例 |
|------|----------|------|
| **内联 reader** | 简单、一次性的参考查询 | Schema 字段内直接写 `reader:` 块 |
| **独立 references/** | 多 Schema 复用同一参考源 | `configs/references/region_lookup.yaml`，Schema 中 `source: region_lookup` 引用 |

两种方式可混用；内联适合快速试验，独立文件适合团队共享维表定义。

### 5.2 内置生成策略

| 策略 | 说明 |
|------|------|
| `random` | 按类型随机生成（整数、浮点、字符串、日期等） |
| `sequence` | 递增/递减序列，可配置起始值与步长 |
| `enum` | 从枚举值列表中选取 |
| `regex` | 按正则模式生成字符串 |
| `reference` | 基于参考数据（维表/采样/种子）生成 |

### 5.3 参考数据三形态

| 阶段 | 形态 | 说明 |
|------|------|------|
| P1 | 维表引用 | 外键/枚举，从 PG/CH/CSV 读维表（CH 在 P1 仅作维表 Reader，采样分布见 P2） |
| P2 | 采样分布 | 直方图/正态拟合，按统计分布生成 |
| P3 | 种子模板 | 以完整记录为模板变异扩展 |

### 5.4 Job 编排（单表与多表）

**单表快捷模式** = 仅含一张表的 Job 配置，与多表共用同一格式：

```yaml
# configs/jobs/single_customer.yaml
job: single_customer
tables:
  - name: customers
    schema: schemas/customer.yaml
    count: 100
```

**多表 DAG 编排：**

```yaml
# configs/jobs/ecommerce_seed.yaml
job: ecommerce_seed
tables:
  - name: customers
    schema: schemas/customer.yaml
    count: 1000

  - name: orders
    schema: schemas/order.yaml
    count: 5000
    constraints: constraints/order_rules.yaml   # 表项级约束（可选，覆盖 Schema 级）
    depends_on: [customers]

  - name: order_items
    schema: schemas/order_item.yaml
    count: 20000
    depends_on: [orders]
```

`dg-core/engine` 对 `depends_on` 做拓扑排序，上游生成结果注入下游 `GenerationContext`，供外键引用。

Job 级也可引用约束集（作用于所有表）：

```yaml
job: ecommerce_seed
constraints: constraints/global_rules.yaml   # 可选，Job 级约束
tables:
  - name: customers
    ...
```

---

## 6. 约束引擎

### 6.1 四层约束

| 层级 | 类型 | 示例 | 阶段 |
|------|------|------|------|
| **字段级** | type, range, nullable, enum, foreign_key, regex | `age: 18~65`、`status IN (...)` | P1 |
| **组合级** | dependency, mutex, conditional, temporal | `pay_type=cash → bank_account IS NULL` | P1 |
| **空间级** | coordinate, topology (JTS) | 点在多边形内、两几何不相交 | P2 |
| **自定义** | SpEL / Aviator / Groovy / 插件 | `#amount > #discount && #discount >= 0` | P1(SpEL) → P2(Aviator/Groovy) |

### 6.2 约束挂载机制

约束规则定义在 `configs/constraints/` 目录，通过 `constraints:` 字段挂载：

| 挂载位置 | 作用范围 | 优先级 |
|----------|----------|--------|
| Schema 级 `constraints: path` | 单表 | 低 |
| Job 级 `constraints: path` | 整个任务所有表 | 中 |
| 表项级 `constraints: path`（Job 内某 table） | 单表（覆盖 Schema 级） | 高 |

加载顺序：Schema 级 → Job 级 → 表项级依次叠加，高优先级覆盖同键约束，合并去重后组成约束管道。

### 6.3 约束配置示例

```yaml
# configs/constraints/order_rules.yaml
constraints:
  # [P1] 字段级
  - level: field
    field: amount
    type: range
    min: 0.01
    max: 99999.99

  - level: field
    field: customer_id
    type: foreign_key
    ref_table: customers
    ref_field: id

  # [P1] 组合级 - SpEL
  - level: composite
    type: conditional
    expression: "pay_type != 'cash' or bank_account == null"
    language: spel

  - level: composite
    type: mutex
    fields: [email, phone]
    rule: at_least_one

  # [P2] 空间级
  - level: spatial
    field: location
    type: within
    geometry_ref: region_boundary

  # [P2] 自定义 - Aviator
  - level: custom
    expression: "amount > discount * 1.1"
    language: aviator
    on_fail: repair             # reject | repair | warn
```

### 6.4 执行流程

```
生成字段值
    │
    ▼
FieldValidators        →  类型/范围/外键/空值
    │
    ▼
CompositeValidators    →  依赖/互斥/条件/时序
    │
    ▼
SpatialValidators      →  JTS 拓扑（可选）
    │
    ▼
ExpressionValidators   →  SpEL/Aviator/Groovy
    │
    ▼
通过 → 写入批次
失败 → 按 on_fail 策略处理
```

### 6.5 设计决策

- **校验时机**：字段级在单字段生成后立即校验；组合/空间/自定义在整行生成完成后校验
- **失败策略**：`reject`（丢弃重试，最多 N 次）、`repair`（自动修正）、`warn`（记录告警仍写入）
- **表达式上下文**：整行字段 + 上游表已生成数据 + 参考数据缓存，统一注入 `ConstraintContext`
- **JTS 可选**：`dg-core` 对 JTS 软依赖，无空间约束时不加载 SpatialValidators

---

## 7. REST API 与任务模型

### 7.1 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/jobs` | 提交生成任务 |
| `GET` | `/api/v1/jobs/{id}` | 查询任务状态与结果 |
| `DELETE` | `/api/v1/jobs/{id}` | 取消进行中的异步任务 |
| `POST` | `/api/v1/preview` | 预览生成（不写库，返回前 N 行） |
| `GET` | `/api/v1/schemas` | 列出本地配置目录中的 Schema |
| `GET` | `/api/v1/schemas/{name}` | 查看 Schema 详情 |
| `GET` | `/api/v1/health` | 健康检查 |

### 7.2 提交任务请求

```json
{
  "jobConfig": "jobs/ecommerce_seed.yaml",
  "overrides": {
    "tables.customers.count": 500,
    "tables.orders.count": 2000
  },
  "writer": {
    "type": "postgresql",
    "connection": "dev-pg",
    "mode": "insert"
  },
  "options": {
    "batchSize": 1000,
    "syncThreshold": 5000,
    "onConstraintFail": "reject",
    "maxRetries": 3
  }
}
```

**overrides 路径规则：** `tables.{name}.{field}`，其中 `{name}` 对应 Job YAML 中 `tables[].name` 字段值（非数组下标）。

### 7.3 预览请求

```json
{
  "jobConfig": "jobs/ecommerce_seed.yaml",
  "overrides": { "tables.customers.count": 10 },
  "preview": {
    "limit": 20,
    "tables": ["customers", "orders"]
  }
}
```

- 忽略 `writer` 配置，不写入任何数据源
- 返回每个指定表的前 N 行（N = `preview.limit`，默认 10，上限 100）
- 响应格式与 §7.5 类似，额外包含 `rows` 数组（生成的样本数据）

### 7.4 同步 / 异步切换

- 预估总行数 ≤ `syncThreshold`（默认 5000）→ 同步执行，HTTP 200 返回 §7.5 格式的完整结果
- 预估总行数 > `syncThreshold` → 创建 Job，返回 202 + `{ "jobId": "..." }`，后台线程池执行，客户端轮询 `GET /jobs/{id}` 获取 §7.5 格式结果

### 7.5 任务状态机

```
PENDING → RUNNING → COMPLETED
                  → FAILED
                  → CANCELLED
```

### 7.6 任务响应示例

```json
{
  "jobId": "job-20260605-001",
  "status": "COMPLETED",
  "progress": {
    "totalTables": 3,
    "completedTables": 3,
    "totalRows": 26000,
    "writtenRows": 26000,
    "failedRows": 0
  },
  "duration": "12.3s",
  "details": [
    { "table": "customers", "rows": 1000, "status": "ok" },
    { "table": "orders", "rows": 5000, "status": "ok" },
    { "table": "order_items", "rows": 20000, "status": "ok" }
  ]
}
```

---

## 8. 配置管理

### 8.1 本地配置目录结构

```
configs/
├── schemas/           # 表/数据集 Schema 定义
│   └── order.yaml
├── references/        # 参考数据读取配置
│   └── region_lookup.yaml
├── constraints/       # 可复用约束规则集
│   └── spatial_rules.yaml
└── jobs/              # 多表编排任务定义（DAG）
    └── ecommerce_seed.yaml
```

### 8.2 应用配置

```yaml
# application.yml
data-generator:
  config-dir: /data/configs
  connections:
    dev-pg:
      type: postgresql
      url: jdbc:postgresql://localhost:5432/dev
      username: ${PG_USER}
      password: ${PG_PASSWORD}
    dev-ch:
      type: clickhouse
      url: jdbc:clickhouse://localhost:8123/analytics
  job:
    sync-threshold: 5000
    thread-pool-size: 4
    batch-size: 1000
```

Reader 与 Writer 配置中的 `connection: "dev-pg"` 均引用此处定义的连接，避免在 Schema YAML 中硬编码凭证。

---

## 9. 错误处理

| 场景 | 策略 |
|------|------|
| Schema YAML 语法/语义错误 | 启动时 + 提交时校验，返回明确错误路径 |
| 数据源连接失败 | 任务标记 FAILED，错误信息含 connection 名称 |
| 约束校验持续失败（超 maxRetries） | 该行跳过，计入 failedRows，任务仍可 COMPLETED（部分成功） |
| 异步任务执行异常 | FAILED + 完整堆栈写入任务日志 |
| 插件未找到 | 400 Bad Request，`Unknown plugin type: xxx` |

---

## 10. 测试策略

| 层级 | 范围 | 方式 |
|------|------|------|
| 单元测试 | generator、constraint、schema 解析 | JUnit 5，无外部依赖 |
| 集成测试 | Reader/Writer 与真实 PG/CH | Testcontainers |
| API 测试 | REST 端点 | `@SpringBootTest` + MockMvc |
| 端到端 | 完整 job 配置 → 生成 → 写入 → 校验 | 测试用 YAML fixture + Testcontainers |

---

## 11. 分阶段交付计划

| 阶段 | 交付内容 |
|------|----------|
| **P1** | 5 模块骨架、PG/CH/CSV 读写、字段级约束、维表引用、单表 + 多表 DAG、REST 同步模式、SpEL 组合约束 |
| **P2** | 采样分布参考数据、异步任务、Aviator 表达式、空间约束(JTS) |
| **P3** | 种子模板、Groovy 自定义插件、约束 repair 策略、任务取消 |

---

## 12. 整体数据流

```
客户端 POST /jobs
    │
    ▼
Orchestrator 解析 YAML，构建 DAG
    │
    ▼
按表顺序（拓扑排序）：
    Reader 拉取参考数据
        │
        ▼
    Generator 逐行生成
        │
        ▼
    Constraint Engine 校验/修正
        │
        ▼
    Writer 批量写入
    │
    ▼
行数 < syncThreshold → 同步返回结果
行数 ≥ syncThreshold → 返回 jobId，后台执行
```
