# Job 级多种子（Seeds）设计规格

**日期：** 2026-06-07  
**状态：** 已批准  
**版本：** 1.0

---

## 1. 背景与目标

### 1.1 现状问题

- `seed` 配置在 `schema` 内，每张表仅一个来源，多数据源只能 SQL JOIN。
- `mutate` 列表决定哪些字段重生成，语义不直观。
- 多个 reader 来源的字段无法声明式关联（如两表同 `id` 对齐）。
- `strategy: seed` 字段实际由 `SeedTemplateApplier` 整表短路处理，与字段 generator 模型不一致。

### 1.2 目标

1. 将 `seeds` 提升到 **Job 顶层**，支持 **多个** 命名数据源。
2. **移除** `schema.seed` 与 `mutate`（不保留兼容层）。
3. 字段通过 `generator: { strategy: seed, source: <name> }` 绑定 seed。
4. 非 `seed` 策略的字段按各自 generator 每行生成（自然替代 mutate）。
5. 可选 `link` 声明 seed 间关联键，保证多 seed 采样同一关联维度（如相同 `id`）。
6. 关联采样失败时 **整行重试**（重新采根 seed，最多 `maxRetries` 次）。

---

## 2. YAML 结构

### 2.1 Job 顶层 seeds

```yaml
id: my_job
name: 示例造数

writer:
  type: postgresql
  connection: dev-pg
  mode: insert

seeds:
  - name: customer
    reader:
      type: postgresql
      connection: dev-pg
      query: |
        SELECT id, name FROM customers
        ORDER BY random() LIMIT 1

  - name: order
    link:
      seed: customer
      on: id
    reader:
      type: postgresql
      connection: dev-pg
      query: |
        SELECT id, amount FROM orders
        WHERE id = :link_id
        LIMIT 1

tables:
  - name: order_export
    count: 1000
    schema:
      table: order_export
      fields:
        - name: id
          generator: { strategy: seed, source: customer, field: id }
        - name: name
          generator: { strategy: seed, source: customer, field: name }
        - name: amount
          generator: { strategy: seed, source: order, field: amount }
        - name: batch_no
          generator: { strategy: sequence, start: 1, step: 1 }
```

### 2.2 Seed 定义

| 字段 | 必填 | 说明 |
|------|------|------|
| `name` | 是 | Job 内唯一标识，供字段 `source` 引用 |
| `reader` | 三选一 | 内联 SQL / 连接配置，采样一行 |
| `reference` | 三选一 | 引用 `references/<name>.yaml` |
| `template` | 三选一 | 内联固定键值 Map |
| `link` | 否 | 与上游 seed 关联；省略则为根 seed |

`link` 子字段：

| 字段 | 必填 | 说明 |
|------|------|------|
| `seed` | 是 | 父 seed 的 `name` |
| `on` | 否* | 父子两侧同名列名；与 `parent_field`/`local_field` 二选一 |
| `parent_field` | 否 | 父 seed 采样行中的列名 |
| `local_field` | 否 | 语义别名，仅文档用途；过滤仍注入 `:link_id` |

\* 若省略 `on` 且未指定 `parent_field`，加载时报错。

### 2.3 字段 generator（seed 策略）

| 参数 | 必填 | 说明 |
|------|------|------|
| `strategy` | 是 | 固定 `seed` |
| `source` | 是 | 对应 `seeds[].name` |
| `field` | 否 | seed 行中的列名；默认等于 schema 字段 `name` |

### 2.4 移除项

- `schema.seed`（含 `reader` / `reference` / `template` / `mutate`）
- 不保留旧格式自动迁移

---

## 3. 运行时行为

### 3.1 Seed 依赖图

- 引擎在 Job 加载后，对 `seeds` 按 `link.seed` 做拓扑排序。
- 无 `link` 的 seed 为 **根 seed**，可多个（彼此独立随机采样）。
- 存在环时加载失败，错误信息同 `depends_on` 循环检测。

### 3.2 每行生成流程

对每张表的每一行：

1. 收集当前表 fields 引用的 seed `source` 集合（含 link 链上的祖先 seed）。
2. 按拓扑序采样：
   - **根 seed**：`sampleRow()` 随机一行。
   - **从属 seed**：从父 seed 采样行取 `link.on`（或 `parent_field`）值作为 `link_id`，执行 reader 查询（替换 `:link_id` 与 `:link.<column>` 占位符），必须返回 **恰好一行**；零行或多行视为关联失败。
3. 得到 `Map<String, DataRow> seedSamples`。
4. 逐字段：
   - `strategy: seed` → 从 `seedSamples.get(source)` 复制 `field` 列。
   - 其他 strategy → 调用对应 Generator。
5. 执行 constraints 校验；失败则整行重试（含 seed 重采），最多 `maxRetries`。

### 3.3 关联失败与重试（方案 A）

从属 seed 查询结果为空或多行时：

- 本次行生成失败，**不**部分写入。
- 若未超过 `maxRetries`，从根 seed 重新采样并重走 link 链。
- 超过重试次数则该行丢弃（与现有 constraint reject 行为一致），计入 `failedRows`。

### 3.4 占位符

从属 seed 的 `reader.query` 支持：

| 占位符 | 含义 |
|--------|------|
| `:link_id` | 当前 link 解析出的关联键值 |
| `:link.<column>` | 父 seed 采样行某列，如 `:link.id` |

由引擎在调用 Reader 前替换为字面量（注意 SQL 注入：仅允许绑定父 seed 已采样值，参数化优先）。

### 3.5 与 constraints / reference 的关系

- **constraints**：seed 字段已在 `currentRow`，SpEL 等表达式直接引用列名。
- **reference**：独立机制，仍用于无 link 关系的维表随机取值；与 job seeds 正交。
- **depends_on + foreign_key**：跨 **表** 关联不变；seeds 解决 **同行多数据源** 对齐。

---

## 4. 模块改动

### 4.1 dg-core

| 组件 | 改动 |
|------|------|
| `SeedDefinition` | 新增：`name`, `link`, reader/reference/template |
| `SeedLinkDefinition` | 新增：`seed`, `on`, `parent_field`, `local_field` |
| `JobDefinition` | 新增 `List<SeedDefinition> seeds` |
| `SchemaDefinition` | 移除 `seed` 字段 |
| `YamlConfigLoader` | 解析 `seeds`；校验 name 唯一、link 引用有效、无环 |
| `SeedDependencySorter` | 新增：seeds 拓扑排序（可复用 DagSorter 逻辑） |
| `SeedSampler` | 新增：按序采样、占位符注入、关联失败检测 |
| `SeedGenerator` | 实现：从 `GenerationContext.seedSamples()` 取列 |
| `GenerationContext` | 新增 `Map<String, DataRow> seedSamples` |
| `TableGenerator` | 移除 `SeedTemplateApplier` 整表短路；每行先 `SeedSampler` 再逐字段生成 |
| `JobOrchestrator` | 将 `job.getSeeds()` 传入 `TableGenerator` |
| `SeedTemplateApplier` | **删除** |

### 4.2 dg-web / 文档

- ~~更新 `config-guide.md`~~（已完成）：Job 级 seeds、link、字段 source；删除 schema.seed / mutate 章节。
- 部署方自行维护 Job YAML（`config-dir/jobs/` 或 `writable-config-dir/jobs/`），不随公开仓库发布。
- 控制台默认模板无需 seed（保持简单）。

### 4.3 dg-spi

- `GenerationContext` record 增加 `seedSamples` 参数（破坏性变更，仅 dg-core 内部使用）。

---

## 5. 校验规则

| 规则 | 时机 | 错误 |
|------|------|------|
| seed `name` 唯一 | Job 加载 | 重复 name |
| `link.seed` 存在 | Job 加载 | 未知 seed |
| link 无环 | Job 加载 | Cycle detected in seed link graph |
| 字段 `source` 存在 | Job 加载 | 未知 seed source |
| seed 策略缺 `source` | Job 加载 | 配置错误 |
| 从属 seed 查询 0 行 | 行生成 | 触发重试 |
| 从属 seed 查询 >1 行 | 行生成 | 触发重试 |
| seed 行缺目标列 | 行生成 | 明确列名错误 |

---

## 6. 测试策略

| 用例 | 说明 |
|------|------|
| `YamlConfigLoaderTest` | 解析 seeds、link、校验失败场景 |
| `SeedDependencySorterTest` | 拓扑序、环检测 |
| `SeedSamplerTest` | 根/从属采样、占位符、关联失败重试 |
| `SeedGeneratorTest` | field 别名、缺列错误 |
| `TableGeneratorTest` | 多 seed 同行、与非 seed generator 混用 |
| 集成 | 精简 fixture Job（如 `fixtures/jobs/ecommerce_seed.yaml`） |

测试命名：`feature_scenario_expected`。

---

## 7. 示例：两 seed 同 id

```yaml
seeds:
  - name: header
    reader:
      type: postgresql
      connection: dev-pg
      query: "SELECT id, customer_name FROM orders ORDER BY random() LIMIT 1"

  - name: detail
    link:
      seed: header
      on: id
    reader:
      type: postgresql
      connection: dev-pg
      query: "SELECT id, line_no, sku FROM order_lines WHERE id = :link_id LIMIT 1"

tables:
  - name: export
    count: 100
    schema:
      table: export
      fields:
        - { name: id, generator: { strategy: seed, source: header, field: id } }
        - { name: customer_name, generator: { strategy: seed, source: header } }
        - { name: line_no, generator: { strategy: seed, source: detail, field: line_no } }
        - { name: sku, generator: { strategy: seed, source: detail } }
```

---

## 8. 非目标

- 不保留 `schema.seed` / `mutate` 兼容。
- 不在本阶段实现 seed 结果跨行缓存或全局预加载（仍 per-row 采样）。
- 不改变 `reference` 策略语义。

---

## 9. 批准记录

| 决策 | 结论 |
|------|------|
| 字段绑定方式 | A：字段级 `source` |
| link 是否必填 | 否；无 link 为根 seed |
| 关联失败 | A：整行重试 |
| 旧格式兼容 | 不保留 |
