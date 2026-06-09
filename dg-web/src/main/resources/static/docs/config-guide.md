# 配置指南

本指南帮助你在 Data Generator 控制台中编写 YAML 任务配置，完成测试数据生成。建议按顺序阅读；熟悉后可查阅文末**策略速查表**。

---

## 快速开始

完成以下三步即可生成第一批数据：

1. **确认连接** — 管理员在 `application.yml` 中配置数据库 / CSV 输出目录（见 [配置连接](#配置连接)）。
2. **编写 Job** — 在控制台点击「新建任务」，填写**任务名称**与 YAML 内容（见 [编写第一个 Job](#编写第一个-job)）。
3. **运行** — 保存后点击「运行」；小任务同步完成，大行数任务自动异步执行，可在「日志」中查看进度。

> **控制台提示：** 自定义任务的**显示名称**在弹窗「任务名称」中填写，**不必**在 YAML 里写顶层 `name`；`id` 也由系统自动生成。定时调度在弹窗中配置，**不要**在自定义 YAML 里写 `schedule` 块。

控制台内置模板可作为起点（不含 `id` / `name`，保存时自动补全）：

```yaml
writer:
  type: csv
  connection: local-csv
  mode: insert
tables:
  - name: customers
    count: 100
    schema:
      table: customers
      fields:
        - name: id
          type: BIGINT
          generator: { strategy: sequence, start: 1, step: 1 }
        - name: name
          type: VARCHAR
          generator: { strategy: random, type: string, minLength: 8, maxLength: 12 }
```

---

## 配置由哪些部分组成

一份完整的任务配置通常包含：

| 部分 | 作用 | 配置位置 |
|------|------|----------|
| **Job** | 任务唯一 ID、描述名称、默认写入方式 | YAML 顶层 `id`、`name`、`writer`；控制台自定义任务另有 UI 字段 |
| **Seeds** | 从真实/维表数据采样作底稿，多源可关联 | 顶层 `seeds[]`（Job 级，非 schema 内） |
| **表任务** | 每张表生成多少行、依赖关系 | `tables[]` |
| **Schema** | 字段列表及每列如何生成 | `tables[].schema` |
| **约束** | 生成结果的校验规则 | `schema.constraints` 或 `tables[].constraints` |
| **连接** | 数据库地址、CSV 目录 | `application.yml`（不在 Job 里写密码） |
| **定时调度** | Cron 自动执行 | 内置 Job：YAML `schedule`；自定义 Job：控制台弹窗（存 SQLite） |

推荐将 Schema **内联**在 Job 中（字段多、业务绑定时更清晰）；多个 Job 共用同一 Schema 时可拆到 `schemas/*.yaml` 并用路径引用。

---

## 在控制台管理任务

通过 Web 控制台新建/编辑**自定义任务**时，与手写 YAML 文件有以下区别：

| 项 | 控制台行为 | YAML 编辑区 |
|----|------------|-------------|
| **任务名称** | 弹窗「任务名称」填写，可中文 | **无需**写顶层 `name`，保存时自动写入 |
| **任务 ID** | 新建时系统自动生成（`job` + 8 位 hex） | **无需**写 `id`，保存时自动写入 |
| **配置文件名** | 默认与自动生成的 `id` 相同（纯 ASCII） | 不展示；存于 `writable-config-dir/jobs/` |
| **定时调度** | 弹窗「定时调度」勾选并填 Cron | **禁止**写 `schedule` 块 |
| **内置任务** | 只读；调度读 YAML，不可在 UI 修改 | 可查看完整 YAML（含 `id`、`name`） |

列表按**内置任务优先**，自定义任务按**创建时间倒序**；任务列表与运行日志均支持分页浏览。

**自动刷新：** 工具栏默认勾选「自动刷新」。页面加载后即开始轮询；有运行中任务或打开日志弹窗时每 **2 秒** 刷新，否则每 **5 秒**。任务列表采用**增量更新**（仅刷新状态列与停止按钮），避免整表重绘导致卡顿或「更多」菜单收起；日志弹窗同步更新运行记录与展开中的详情。

### 定时调度（Cron）

- 表达式格式：**Spring 6 字段**（秒 分 时 日 月 周），服务器本地时区
- 示例：`0 0 2 * * ?` 表示每天凌晨 2 点
- 同一配置若已有运行中实例，新触发会**排队**（FIFO），不会并行重复执行
- 已启用调度时点击「运行」，会**立即执行一次**，不影响 Cron 计划

内置 Job 可在 YAML 中声明调度：

```yaml
id: my_builtin_job
name: 内置定时任务
schedule:
  enabled: true
  cron: "0 0 2 * * ?"
tables: []
```

---

## 编写第一个 Job

### 最小结构

手写 YAML 文件（或查看内置任务）时：

```yaml
id: my_job_name           # 任务唯一标识，必填，全局不可重复
name: 我的业务造数任务     # 任务描述名称，说明用途，可中文

writer:                   # 默认写入方式（可被表级覆盖）
  type: csv               # csv | postgresql | clickhouse
  connection: local-csv   # 连接名，见 application.yml
  mode: insert

tables:                   # 至少一张表
  - name: customers       # 逻辑名，多表引用时用此名
    count: 100            # 生成行数
    schema:
      table: customers    # 物理表名 / CSV 文件名
      fields:             # 字段列表
        - name: id
          type: BIGINT
          generator: { strategy: sequence, start: 1, step: 1 }
```

在控制台**新建自定义任务**时，编辑区只需写 `writer`、`tables` 等业务配置；`id`、`name` 由系统与「任务名称」输入框分别维护。

### 顶层字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| `id` | 是* | 任务唯一标识，仅含字母、数字、下划线、连字符，以字母开头；全局不可重复。*控制台新建自定义任务时自动生成 |
| `name` | 否 | 任务描述名称，可中文。*控制台自定义任务请在「任务名称」填写，勿在 YAML 重复 |
| `writer` | 否 | Job 级默认写入配置，可被表级覆盖 |
| `seeds` | 否 | Job 级命名种子列表，供字段 `strategy: seed` 引用（见 [Job 级 seeds](#job-级-seeds从真实数据出发)） |
| `schedule` | 否 | 定时调度（仅**内置** Job；自定义任务请用控制台） |
| `constraints` | 否 | 引用外部约束文件或内联约束列表 |
| `tables` | 是 | 表任务列表 |

> **说明：** 旧版配置中的 `job` 字段已废弃，请改用 `id` + `name`。加载旧文件时仍会临时兼容读取 `job`，但保存时必须使用 `id`。

### 每个字段怎么写

每个字段需要三样东西：

```yaml
- name: sgbh              # 列名，与数据库表一致
  type: VARCHAR           # 逻辑类型，便于阅读
  generator:              # 如何生成值
    strategy: regex
    pattern: '30000[0-9]{16}'
```

`generator` 用 `strategy` 选择生成方式，其余参数因策略而异（见 [选择生成策略](#选择生成策略)）。

---

## 配置连接

连接信息放在 **`application.yml`**，Job 里只写连接**名称**，避免把密码写进业务配置。

```yaml
data-generator:
  connections:
    dev-safety:              # ← Job 里 writer.connection 引用此名
      type: postgresql
      url: jdbc:postgresql://host:5432/SAFETY_DB
      username: postgres
      password: ******
    local-csv:
      type: csv
      path: ./output         # CSV 输出根目录
    traffic-output:
      type: csv
      path: ./output/traffic
```

| 字段 | 何时必填 | 说明 |
|------|----------|------|
| `type` | 始终 | `postgresql` / `clickhouse` / `csv` |
| `url` | 数据库 | JDBC 地址 |
| `username` / `password` | 数据库 | 凭证 |
| `path` | CSV | 文件输出目录 |

修改连接后需**重启服务**；Job YAML 无需改动。

---

## 选择生成策略

根据「这一列需要什么数据」选择策略：

| 我想要… | 使用策略 | 示例 |
|---------|----------|------|
| 自增 ID | `sequence` | `{ strategy: sequence, start: 1, step: 1 }` |
| 随机整数 / 小数 / 字符串 | `random` | `{ strategy: random, type: int, min: 0, max: 100 }` |
| 随机日期时间 | `random` + `datetime` | `{ strategy: random, type: datetime, min: '2024-01-01 00:00:00', max: '2024-12-31 23:59:59' }` |
| 从固定列表随机 | `enum` | `{ strategy: enum, values: [A, B, C] }` |
| 按正则格式 | `regex` | `{ strategy: regex, pattern: '440115[0-9]{8}' }` |
| 引用上游表某列 | `reference` | `{ strategy: reference, source: orders, field: id }` |
| 从 Job seeds 采样 | `seed` | `{ strategy: seed, source: location_sample }`（见 [Job 级 seeds](#job-级-seeds从真实数据出发)） |
| 表达式计算列值 | `expression` | `{ strategy: expression, expression: "price * qty", language: spel }` |

### sequence — 递增编号

```yaml
generator: { strategy: sequence, start: 1, step: 1 }
```

每张表按**行号**递增：`第 n 行 = start + n × step`（从 0 起算），适合主键、序号；大表并行生成时仍保持 deterministic。

### random — 随机值

`type` 决定随机类型：

| type | 主要参数 | 说明 |
|------|----------|------|
| `int` | `min`, `max` | 整数，含边界 |
| `long` | `min`, `max` | 长整数 |
| `double` | `min`, `max` | 浮点数 |
| `string` | `minLength`, `maxLength` | 随机字母数字串 |
| `date` | `min`, `max` | 日期 `yyyy-MM-dd` |
| `datetime` | `min`, `max` | 日期时间，**推荐用于 TIMESTAMP 列** |

```yaml
- name: sgfssj
  type: TIMESTAMP
  generator: { strategy: random, type: datetime, min: '2024-10-01 00:00:00', max: '2024-12-31 23:59:59' }
```

### enum — 枚举随机

```yaml
generator: { strategy: enum, values: [0, 0, 0, 1] }
```

- 列表中**重复值**相当于加权（上例约 25% 为 1）
- 空字符串 `''` 表示空值；写入 PostgreSQL 时会转为 `NULL`

### regex — 格式字符串

```yaml
generator: { strategy: regex, pattern: '1[3-9][0-9]{9}' }
```

适合身份证号、编号等有固定格式的字段。

### reference — 引用其他表

引用**同一 Job 内已生成的上游表**：

```yaml
# 上游表 orders 已配置 depends_on
- name: order_id
  generator: { strategy: reference, source: orders, field: id }
```

引用外部数据库维表时，需额外配置 `reader`：

```yaml
generator:
  strategy: reference
  source: region_lookup
  field: code
  reader:
    type: postgresql
    connection: dev-pg
    query: "SELECT code FROM regions"
```

可选 `distribution`：`uniform`（默认）、`histogram`、`normal`，用于控制数值型参考数据的采样分布。

### seed — 从 Job 级 seeds 采样

```yaml
generator: { strategy: seed, source: location_sample }
generator: { strategy: seed, source: location_sample, field: place_name }   # 列名与 schema 字段不同时指定 field
```

- `source` 必填，对应顶层 `seeds[].name`
- `field` 可选，默认等于 schema 字段名
- 非 `seed` 策略的字段按各自 generator 每行独立生成（无需 `mutate` 列表）

### expression — 表达式计算

基于当前行已生成的字段计算列值，适合派生列、简单换算：

```yaml
generator:
  strategy: expression
  expression: "amount * 1.1"
  language: spel    # 可选：spel（默认）| aviator | groovy
```

表达式中直接使用 schema 字段名；依赖字段须在本字段之前已生成（按 YAML 字段顺序）。

---

## Job 级 seeds：从真实数据出发

当你希望「先从生产/路网库采一行真实数据作底稿，部分列保留采样值、其余列用 generator 重算」时，在 **Job 顶层** 声明 `seeds`，字段通过 `strategy: seed` 绑定。

> **说明：** 旧版 `schema.seed` + `mutate` 已移除，请迁移为 Job 级 `seeds` 格式。

### 基本写法

```yaml
id: incident_demo
name: 事件造数示例

writer:
  type: csv
  connection: local-csv
  mode: insert

seeds:
  - name: location_sample
    reader:
      type: postgresql
      connection: dev-pg
      query: |
        SELECT name AS place_name, region_code, st_astext(geom) AS geom
        FROM dim_location
        WHERE id > 0
        ORDER BY random() LIMIT 1

tables:
  - name: incidents
    count: 1000
    schema:
      table: incidents
      fields:
        - name: place_name
          generator: { strategy: seed, source: location_sample }
        - name: region_code
          generator: { strategy: seed, source: location_sample }
        - name: geom
          generator: { strategy: seed, source: location_sample }
        - name: incident_no
          generator: { strategy: regex, pattern: 'INC[0-9]{12}' }
        - name: occurred_at
          generator: { strategy: random, type: datetime, min: '2024-10-01 00:00:00', max: '2024-12-31 23:59:59' }
        - name: longitude
          generator: { strategy: enum, values: [0] }
```

### 每个 seed 的字段

| 字段 | 必填 | 说明 |
|------|------|------|
| `name` | 是 | Job 内唯一标识，供字段 `source` 引用 |
| `reader` | 三选一 | 内联 SQL / 连接配置，每次采样一行 |
| `reference` | 三选一 | 引用 `references/*.yaml` 中预定义的 reader |
| `template` | 三选一 | 内联固定键值 Map |
| `link` | 否 | 与上游 seed 关联，保证多源同行对齐（见下） |

### 三种数据来源（三选一）

| 方式 | 配置 | 适用 |
|------|------|------|
| **SQL 采样** | `reader` + `query` | 从数据库随机取一行（最常用） |
| **引用配置** | `reference: 名称` | 复用 `references/*.yaml` |
| **内联模板** | `template: { 字段: 值 }` | 固定底稿，适合简单场景 |

### 多 seed 关联（link）

多个数据源需在同一关联维度对齐（如相同 `id`）时，为从属 seed 配置 `link`：

```yaml
seeds:
  - name: customer
    reader:
      type: postgresql
      connection: dev-pg
      query: "SELECT id, name FROM customers ORDER BY random() LIMIT 1"

  - name: order
    link:
      seed: customer
      on: id              # 父 seed 采样行的 id 列作为关联键
    reader:
      type: postgresql
      connection: dev-pg
      query: "SELECT id, amount FROM orders WHERE id = :link_id LIMIT 1"
```

从属 seed 的 query 支持占位符：

| 占位符 | 含义 |
|--------|------|
| `:link_id` | 当前 link 解析出的关联键值 |
| `:link.<column>` | 父 seed 采样行某列，如 `:link.region` |

关联查询须返回**恰好一行**；失败时整行重试（重新采根 seed），超过重试次数则丢弃该行。

### 部分 seed 无数据

根 seed 的 SQL 查询**允许返回 0 行**：引擎不会因此中断整个 Job，该 seed 对应字段生成 **null**（与非 seed 字段空值行为一致）。适用于多个 seed 源中仅部分库表有数据的场景。

> **注意：** 从属 seed 的 `link` 关联查询仍须返回恰好一行；`reference` 维表引用策略在无数据时仍会报错（与 seed 语义不同）。

### SQL 采样技巧

在 query 中使用数据库随机函数，例如 PostgreSQL：

```sql
SELECT ST_LineInterpolatePoint(geom, random()) AS geom, ...
```

几何字段用 `st_astext(geom)` 输出 WKT 字符串；写入 PostGIS 的 `geom` 列时会自动转换。

---

## 多表关联

多张表有主从关系时：

```yaml
tables:
  - name: orders
    count: 1000
    schema: { ... }

  - name: order_items
    count: 10
    depends_on: [orders]       # 必须等 orders 生成完
    schema:
      fields:
        - name: order_id
          generator: { strategy: reference, source: orders, field: id }
    constraints:
      - level: field
        field: order_id
        type: foreign_key
        ref_table: orders
        ref_field: id
```

要点：

- `depends_on` 声明执行顺序（引擎自动拓扑排序）
- `reference` 的 `source` 填**逻辑表名**（`tables[].name`），不是物理表名
- `foreign_key` 约束可保证从表字段一定在上游存在

---

## 约束规则

约束在生成后校验每一行。可在表级内联编写：

```yaml
constraints:
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

  - level: composite
    type: conditional
    expression: "amount > 0"
    language: spel
```

### 常用约束类型

| level | type | 用途 |
|-------|------|------|
| field | `range` | 数值在 min～max 之间 |
| field | `nullable` | 不允许为空 |
| field | `foreign_key` | 值须存在于上游表 |
| composite | `conditional` | 表达式为 true 才通过 |
| composite | `mutex` | 多个字段至少一个有值（`rule: at_least_one`） |
| custom | — | 自定义表达式（同 conditional，`level: custom`） |

### 表达式语言

`language` 可选 `spel`（默认）、`aviator`、`groovy`。表达式中可直接使用当前行的字段名。

### 校验失败怎么办

每条约束可设 `on_fail`：

| 值 | 行为 |
|----|------|
| `reject` | 丢弃该行并重试（默认，最多 3 次） |
| `repair` | 尝试自动修正（如 range 截断到边界） |
| `warn` | 记录警告但保留该行 |

---

## 指定写入目标

### Job 级默认 Writer

```yaml
writer:
  type: postgresql
  connection: dev-safety
  mode: insert
```

### 表级覆盖

同一份 Job 中，不同表可写入不同目标：

```yaml
tables:
  - name: orders
    writer:
      type: postgresql
      connection: dev-safety
      mode: insert
    schema:
      table: orders

  - name: order_items
    writer:
      type: csv
      connection: traffic-output
      mode: insert
    schema:
      table: order_items
```

**优先级**：表级 writer > Job 级 writer > API 请求中的 writer。

| type | 说明 |
|------|------|
| `postgresql` | INSERT 到 PG 表（`schema.table`） |
| `clickhouse` | INSERT 到 ClickHouse |
| `csv` | 输出为 `{path}/{table}.csv` |

当前仅支持 `mode: insert`。

### 写入 PostgreSQL 时注意

| 场景 | 建议 |
|------|------|
| TIMESTAMP 列 | 使用 `random` + `type: datetime` |
| 空值 | `enum` 中使用 `''`，或省略该字段的 generator |
| PostGIS `geom` 列 | seed 提供 WKT；列名须为 `geom` |
| 固定经纬度 | `{ strategy: enum, values: [0] }` |

---

## 运行、预览与调整

### 在控制台运行

1. 保存 Job 配置（含可选的定时调度）
2. 点击「运行」— 服务按 YAML 执行；若已启用 Cron，会提示「立即执行一次」
3. 点击「日志」查看该任务的历史运行记录（分页）；展开可查看单次运行的详细日志
4. 运行中任务会**按批次**更新进度（`written_rows`）与日志；控制台自动刷新时保留滚动位置，任务列表增量更新状态
5. 大行数任务（超过 `sync-threshold`，默认 5000）自动异步，需等待完成后查看
6. 任务列表支持分页（每页 20 条）；工具栏「自动刷新」默认开启（运行中 2 秒 / 空闲 5 秒）

### 通过 API 运行（可选）

```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{"jobConfig": "jobs/my_job.yaml", "overrides": {"tables.customers.count": 500}}'
```

**overrides** 目前仅支持覆盖 `tables.{表名}.count`。

### 预览（不写库）

```bash
curl -X POST http://localhost:8080/api/v1/preview \
  -H "Content-Type: application/json" \
  -d '{"jobConfig": "jobs/my_job.yaml", "preview": {"limit": 5}}'
```

返回 JSON，包含 `status`（`COMPLETED`）、`duration` 及各表样本：

```json
{
  "status": "COMPLETED",
  "duration": "120ms",
  "tables": [
    {
      "tableName": "incidents",
      "schemaTable": "incidents",
      "columns": ["incident_no", "place_name", "..."],
      "rows": [{ "incident_no": "INC000...", "place_name": "..." }]
    }
  ]
}
```

不触发任何写入。可通过 `overrides` 覆盖 `tables.{表名}.count`（预览行数仍受 `preview.limit` 约束）。

### 取消任务

控制台「停止」或 `DELETE /api/v1/jobs/{id}`；仅对进行中的任务有效。

---

## 完整示例：多表 + seeds + reference

以下为自包含示例（表名、SQL 均为虚构，请按实际业务替换）：

```yaml
id: incident_demo
name: 事件造数示例

writer:
  type: csv
  connection: local-csv
  mode: insert

seeds:
  - name: location_sample
    reader:
      type: postgresql
      connection: dev-pg
      query: |
        SELECT st_astext(geom) AS geom, name AS place_name, region_code
        FROM dim_location
        ORDER BY random() LIMIT 1

tables:
  - name: incidents
    count: 1000
    schema:
      table: incidents
      fields:
        - { name: place_name, type: VARCHAR, generator: { strategy: seed, source: location_sample } }
        - { name: geom, type: GEOMETRY, generator: { strategy: seed, source: location_sample } }
        - { name: incident_no, type: VARCHAR, generator: { strategy: regex, pattern: 'INC[0-9]{12}' } }
        - { name: occurred_at, type: TIMESTAMP, generator: { strategy: random, type: datetime, min: '2024-10-01 00:00:00', max: '2024-12-31 23:59:59' } }
        - { name: longitude, type: DOUBLE, generator: { strategy: enum, values: [0] } }
        - { name: latitude, type: DOUBLE, generator: { strategy: enum, values: [0] } }

  - name: incident_parties
    count: 10
    depends_on: [incidents]
    schema:
      table: incident_parties
      fields:
        - { name: incident_no, type: VARCHAR, generator: { strategy: reference, source: incidents, field: incident_no } }
        - { name: party_no, type: VARCHAR, generator: { strategy: sequence, start: 1, step: 1 } }
    constraints:
      - { level: field, field: incident_no, type: foreign_key, ref_table: incidents, ref_field: incident_no }
```

---

## 策略速查表

### random 参数

| type | 参数 | 默认值 |
|------|------|--------|
| int | min, max | 0 ~ MAX_INT |
| long | min, max | 0 ~ MAX_LONG |
| double | min, max | 0.0 ~ 1.0 |
| string | minLength, maxLength | 8 |
| date | min, max | 1970-01-01 ~ 2099-12-31 |
| datetime | min, max | 含时间的范围 |

### 约束 level 执行顺序

`field` → `composite` → `spatial` → `custom`

### application.yml 任务与存储参数

| 参数 | 默认 | 说明 |
|------|------|------|
| `job.sync-threshold` | 5000 | 超过则异步 |
| `job.batch-size` | 1000 | 写入批次；进度/日志按批更新（SQLite 持久化有节流，表完成时强制落盘） |
| `job.thread-pool-size` | 4 | 异步任务线程池；单表行数 ≥5000 时并行生成行数据 |
| `storage.sqlite-path` | `./data/dg-jobs.db` | 任务记录 SQLite 库 |
| `storage.log-dir` | `./data/job-logs` | 运行日志文件目录（每任务一个 `{jobId}.log`） |

---

## 常见问题

**Q：保存后运行报错「Unknown connection」**  
A：检查 `writer.connection` / `seeds[].reader.connection` 是否在 `application.yml` 的 `connections` 中定义，且服务已重启。

**Q：PostgreSQL 写入 bigint 列失败**  
A：不要用空字符串表示空值；改用 `enum: ['']` 或不生成该字段。

**Q：从表 reference 报「upstream has no data」**  
A：确认已配置 `depends_on`，且上游表名与 `source` 一致。

**Q：seed 字段值为空或报 Unknown seed source**  
A：确认顶层 `seeds[].name` 与字段 `generator.source` 一致；`strategy: seed` 须显式指定 `source`。若 seed 的 SQL 查询无结果，对应字段为 **null**，任务仍会继续（见 [部分 seed 无数据](#部分-seed-无数据)）。

**Q：多表 Job 第二表很慢或内存占用高**  
A：确认已配置 `depends_on` 与 `reference align: index`；引擎会对 FK 校验建索引，并在上游表完成后仅保留下游所需列。单表 ≥5000 行时可调大 `job.thread-pool-size` 提升并行度。

**Q：link 关联 seed 采样失败**  
A：检查从属 seed 的 query 是否使用 `:link_id` 占位符，且关联查询能返回恰好一行。

**Q：如何只改生成行数而不改 YAML**  
A：API 提交时使用 `"overrides": {"tables.incidents.count": 100}`。

**Q：内置任务和自定义任务有什么区别**  
A：内置任务随 jar 发布只读，调度可在 YAML 中配置；自定义任务保存在 `writable-config-dir/jobs/`，可在控制台编辑，`id`/显示名称/调度由 UI 管理，YAML 中勿写 `schedule`。

**Q：控制台保存后 YAML 里没有 name / id**  
A：正常。编辑区仅展示业务配置；`name` 来自「任务名称」，`id` 与配置文件名在保存时自动生成（文件名为 ASCII，避免中文路径编码问题）。

**Q：启用定时调度但未填 Cron 能否保存**  
A：不能。启用调度时必须填写合法的 Cron 表达式，否则保存失败。

---

## 下一步

- 在控制台「新建任务」中粘贴模板并开始编辑
- 对照上文 [完整示例](#完整示例多表--seeds--reference) 了解 Job 级 seeds 与多表 reference 写法
- 遇到问题先查看运行日志，再对照本指南相应章节
