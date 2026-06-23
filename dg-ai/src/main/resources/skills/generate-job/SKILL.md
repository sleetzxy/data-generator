---
name: generate-job
description: 为 Data Generator 项目编写 YAML Job 配置（表结构、生成策略、seeds、约束、多写）。在用户要求创建/编写/生成造数任务、Job YAML、测试数据配置时使用。须逐步与用户确认 writer/reader、行政区划等地区特征，禁止臆造。仅在对话中输出 YAML，不写入本地文件。
---

# 生成 Job 配置

为 **Data Generator** 项目编写 YAML 任务配置。完整策略与约束说明见 [reference.md](reference.md)；用户文档见 `dg-web/src/main/resources/static/docs/config-guide.md`。

## 开始前

1. 阅读 `AGENTS.md` 与 `.cursor/rules/` 项目规则
2. 确认输出场景（见下表）
3. 查阅现有 Job 作为风格参考：先 `listJobDefinitions`，再 `getJobYaml(fileName)`
4. 调用 **`listConnections`** 列出可用连接供用户选择；**未确认前不在 YAML 中写死 reader/writer**

## 对话策略

**目标：** 高效生成可用 YAML，避免机械盘问。

**原则：**

- **已给出的不重复问**；用户首条消息中的表结构、连接、参考 Job、行数等直接采纳
- **缺什么再问什么**；一轮可合并 2～3 个仍缺失的相关问题，不必严格「一轮只问一项」
- **参考 Job 必须先读**：用户提到某 Job / 某 seed 时，先 `listJobDefinitions` → **`getJobYaml(fileName)`**，以读到的 YAML 为准复用 writer、seeds、区划、SQL；禁止猜测
- **未确认前不臆造** connection、区划码、enum（可用 `listConnections` 展示选项）

**典型流程（可跳过已满足步骤）：**

| 步骤 | 内容 |
|------|------|
| 1 | 明确目标表 / 是否参考某 Job；若参考则 **getJobYaml** |
| 2 | writer / writers（可参考已有 Job 或 listConnections） |
| 3 | seeds（若参考 Job 已含且用户同意复用，整段沿用） |
| 4 | 地区特征、enum、行数等**仍缺失项**（分批或合并提问） |
| 5 | validateJobYaml →（可选 previewJobYaml）→ 输出 YAML |

## 可用工具（dg-web REST，须优先于臆测）

| 工具 | 用途 |
|------|------|
| `listConnections` | 列出已注册连接名与类型（不含 url/密码） |
| `listJobDefinitions` / `getJobYaml` | 浏览与读取已有 Job YAML |
| `listSchemas` / `getSchema` | 读取 Schema 字段与 generator 模板 |
| `validateJobYaml` | 校验 YAML 语法与引擎规范（**输出前必调**） |
| `previewJobYaml` | 小样本预览生成结果（不写库，可选） |
| `createJobDefinition` / `updateJobDefinition` | **仅当用户明确要求保存到 Web 控制台时**调用 |
| `deleteJobDefinition` | 仅当用户明确要求删除自定义 Job 时 |
| `getJobDefinitionSchedule` | 查看内置/已保存 Job 的 cron 调度 |
| `submitJob` / `getSubmittedJob` / `getSubmittedJobLogs` | 用户要求试运行或查运行时 |
| `listSubmittedJobs` / `cancelSubmittedJob` | 查看或取消运行中任务 |

**默认交付：** 对话中输出 YAML 代码块，**不主动**调用 `createJobDefinition` 落盘。用户说「保存到控制台 / 创建任务」时再调用写接口。

用户说「按某 Job 同款 seeds/连接」且 getJobYaml 已读到：只需 **一次确认**「是否整段复用 xxx 的 seeds 与 connection」，不必逐 seed 重问。

## 逐步确认（仅当信息不足时）

以下**旧版逐轮清单**仅在用户未提供、且无法从 getJobYaml / listConnections 获取时适用；**不要**在信息已齐时仍逐步盘问：

| 轮次 | 确认内容 | 通过后再进行 |
|------|----------|--------------|
| 1 | 输出场景（控制台 / 内置文件）、目标物理表、是否有 DDL/样例/参考 Job | 轮次 2 |
| 2 | **writer / writers**：类型、单写/多写、连接名或 url | 轮次 3 |
| 3 | 是否需要 **seeds**；若需要，再逐 seed 确认 **reader** 连接 | 轮次 4 |
| 4 | 各 seed 的采样 SQL / `link` 关联（一个 seed 一轮，复杂 SQL 可拆字段） | 轮次 5 |
| 5 | **地区特征**：区划码、编号前缀地区段、辖区相关字段（按字段或按表分批） | 轮次 6 |
| 6 | 行数、表间 `depends_on`、关键 **业务 enum**（按字段分批，不一次列全表枚举） | 轮次 7 |
| 7 | 汇总已确认项 → 输出完整 YAML | — |

用户中途补充或修改时：回到对应轮次重新确认，再继续后续步骤。

## 必须与用户确认（禁止臆造）

以下信息**不得臆造**；缺信息时按上节**逐步**询问，不得从其他 Job 照搬或凭常识填充：

| 类别 | 须确认内容 |
|------|------------|
| **writer / writers** | 写入类型、连接方式、具体 `connection` 名或 JDBC、是否多写 |
| **reader**（seeds / reference） | 读取类型、连接、目标库/表、采样 SQL；从属 seed 的 `link` |
| **地区特征** | 行政区划代码、辖区、编号前缀地区段、派出所/警格等地域维表 |
| **业务枚举** | 警情类别、证件类型、状态码等 `enum.values` |
| **表结构** | 列名、类型、主键、物理表名 |

**可以做的：** 调用 `listConnections` 展示连接选项；用 `getSchema` 辅助表结构；引用用户提供的 DDL/样例/已有 Job。

**禁止做的：** 未确认就写连接/区划/SQL；一次性列出 5+ 条待确认项让用户填表；从 `city_jq.yaml` 等范例复制地区/连接/SQL。

仅当用户明确「按某已有 Job 同款连接/区划」时，才可复用，且仍须**单独一轮**得到用户确认。

## 交付方式（默认仅输出展示）

**默认：在对话中以 Markdown 代码块输出完整 YAML，供用户复制使用。**

- **禁止**使用 Write/StrReplace 等工具将 Job 写入本地 `configs/jobs/` 路径
- **禁止**主动调用 `createJobDefinition`，除非用户**明确要求**「保存到 Web 控制台 / 创建任务定义」
- 用户明确要求保存时：先 `validateJobYaml` 通过，再 `createJobDefinition` 或 `updateJobDefinition`
- 可说明用户自行保存的参考路径（见下），但路径仅为指引，不由代理落盘

用户保存与运行方式（在回复中提示即可）：

| 方式 | 用户操作 |
|------|----------|
| Web 控制台 | 复制 YAML → 新建任务 → 粘贴 → 保存 → 运行 |
| 内置 Job | 用户自行保存到 `dg-web/src/main/resources/configs/jobs/{id}.yaml` 后重启/刷新 |
| 自定义 overlay | 用户自行保存到 `./data/configs/jobs/` |

## 输出场景

| 场景 | YAML 应包含 | 不应包含 |
|------|-------------|----------|
| **内置 Job 文件** | `id`、`name`、`writer`/`writers`、`tables` | — |
| **控制台自定义 Job** | `writer`/`writers`、`tables`、业务字段 | 顶层 `id`、`name`、`schedule` |
| **内置定时任务** | 上栏全部 + `schedule.enabled` + `schedule.cron` | 控制台场景勿写 `schedule` |

**`id` 规则：** 字母开头，仅含字母、数字、下划线、连字符，全局唯一。

**用户自行保存时的参考路径**（代理不写盘，仅作说明）：

- 内置：`dg-web/src/main/resources/configs/jobs/{id}.yaml`
- 自定义：`writable-config-dir/jobs/`（默认 `./data/configs/jobs/`）

## 工作流程

**多轮对话**推进；每轮结束于「等待用户回复」，勿在同一轮内连问多步。

```
Job 生成进度（逐步确认）：
- [ ] 1. 场景 + 目标表 / DDL
- [ ] 2. 确认 writer / writers
- [ ] 3. 确认是否需要 seeds → 各 reader 连接
- [ ] 4. 各 seed SQL / link（逐 seed）
- [ ] 5. 地区特征（分批）
- [ ] 6. 行数、表关系、业务 enum（分批）
- [ ] 7. 汇总 → 对话中输出 YAML（不写本地文件）
```

**不要**将「验证」理解为代理必须落盘或代用户运行 Job；YAML 输出后可在回复末尾提示用户粘贴到控制台试运行。

### 1. 第一轮：场景与表结构

若用户尚未说明，**只问**：

- 控制台自定义 Job 还是内置 YAML 文件？
- 目标物理表名；是否有 DDL、样例行或参考 Job？

无表结构时请用户提供；**本轮不要**同时问 writer、区划、行数。

用户回复后进入 writer 确认轮。

### 2. 第二轮：writer / writers

**只问写入目标**（可调用 `listConnections` 展示选项）：

- 单写还是多写？
- 类型与 `connection` 名（或内联 url，若用户倾向内联）

确认后再问 seeds；**本轮不要**问 SQL 或 enum。

### 3. 第三轮及以后：seeds、地区、业务细节

按 [确认顺序](#逐步确认禁止一次性问完) 表逐轮推进：

- 需要 seeds → 先确认要不要，再逐个 seed 问 reader
- 地区相关字段 → 按字段或逻辑分组，一批 2～3 个字段为宜
- 业务 enum → 按字段逐个或小批确认 `values`

全部必要项确认完毕后，再编写并输出完整 YAML。

### 结构模式参考

**最小 Job（单表 CSV）：**

```yaml
id: my_job
name: 示例任务
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
          primaryKey: true
          generator: { strategy: sequence, start: 1, step: 1 }
```

**多写（PG + ClickHouse）：** 用 `writers` 列表，勿与 `writer` 并存。

**Job 级 seeds + 字段绑定**（`reader` 连接与 SQL 须在前序轮次确认后填写）：

```yaml
seeds:
  - name: dim_sample
    reader:
      type: postgresql
      connection: <用户确认的 connection 名>
      query: <用户确认的 SQL>
tables:
  - name: fact_table
    count: 1000
    schema:
      table: fact_table
      fields:
        - name: col1
          generator: { strategy: seed, source: dim_sample, default: '' }
```

**多 seed 关联：** 从属 seed 用 `link.seed` + `link.parent_field`（优先于 `on`，避免 YAML 布尔解析陷阱）；query 用 `:link_id` 或 `:link.<column>`。

**多表主从：**

```yaml
tables:
  - name: orders
    count: 1000
    schema: { ... }
  - name: order_items
    count: 10
    depends_on: [orders]
    schema:
      fields:
        - name: order_id
          generator: { strategy: reference, source: orders, field: id }
```

`reference.source` 填逻辑表名 `tables[].name`，不是 `schema.table`。

### 3. 编写 YAML 要点

**writer / reader 连接：**

- 写入/读取的 `type`、`connection` 或内联 `url` **必须经用户确认**
- 可调用 **`listConnections`** 展示可用连接供用户挑选
- 用户选定命名连接后：

```yaml
writer:
  type: postgresql
  connection: <用户确认的连接名>
  mode: insert
```

内联 url 仅在用户明确要求时使用；勿默认内联或从其他 Job 复制 JDBC。

**字段顺序（必须遵守）：**

- `idcard` 派生列（`from: sfzh, part: gender/age/birth_date`）放在 `sfzh` 之后
- `expression` 依赖的字段须在其之前
- `reference` 依赖的上游表须已通过 `depends_on` 先生成

**类型与策略匹配：**

| 列类型 | 策略建议 |
|--------|----------|
| TIMESTAMP | `random` + `type: datetime`（不配 format） |
| VARCHAR 时间 | 同上 + `format: 'yyyy-MM-dd HH:mm:ss'` |
| 自增业务编号 | `sequence` + `prefix` + `width`（`type` 须字符串） |
| 主键 UUID | `uuid` |
| PostGIS geom | seed 提供 WKT；列名 `geom` |
| 空值 | `enum` 含 `''` 或 `default: ''` |

**`prefix` / `width`：** 仅用于字符串类型；`sequence` 优于 `regex` 模拟自增。

### 4. 自检清单

- [ ] **writer / writers / 各 seed.reader 均已获用户确认**（非范例照搬）
- [ ] **行政区划、areaCode、编号地区段、地域 enum 均已获用户确认**
- [ ] `writer` 与 `writers` 未同时使用（Job 级、表级均检查）
- [ ] `tables` 非空；每字段有 `name`、`type`、`generator`
- [ ] `schema.table` 与目标库物理表名一致
- [ ] seed 的 `source` 与 `seeds[].name` 对应
- [ ] `link` 未使用未加引号的 `on:`（用 `parent_field`）
- [ ] 控制台自定义 YAML 无 `id`/`name`/`schedule`
- [ ] **未将 YAML 写入本地文件**（仅在对话中输出）
- [ ] 敏感信息：优先命名连接，避免密码进 Job（若用户明确要求内联则照做）

### 5. 验证

1. **必做：** 调用 `validateJobYaml`；未通过则修正后重试
2. **可选：** 用户需要看样例数据时，调用 `previewJobYaml`（`limitPerTable` 建议 3～10）
3. **可选：** 用户要求试运行时，调用 `submitJob` 并可用 `getSubmittedJob` / `getSubmittedJobLogs` 跟踪

代理**不写入本地文件**。YAML 输出后，可在回复中提示用户粘贴到 Web 控制台或明确要求时再 `createJobDefinition`。

## 回复用户时的格式

**确认进行中（多数轮次）：**

1. **当前进度**：已完成哪几步（简短一句）
2. **本轮唯一问题**：1～2 个具体问题或选项；**不要**附带其他未轮到的待确认项
3. **（可选）** 若有助于决策，只展示与本轮相关的连接列表或字段片段

**全部确认完毕后：**

1. **摘要**：表、行数、已确认的 writer/reader、是否 seeds/多表
2. **完整 YAML**（Markdown 代码块，供复制）
3. **已确认项回顾**（连接、区划、关键 enum 的来源）
4. **使用提示**：控制台粘贴 / 用户自行保存的路径说明（不写盘）

## 参考资源

| 资源 | 用途 |
|------|------|
| [reference.md](reference.md) | 策略速查、约束、项目 Job 模式 |
| `listConnections` / `getJobYaml` / `getSchema` | 运行时查连接、Job、Schema |
| `dg-web/src/main/resources/static/docs/config-guide.md` | 权威用户文档 |

## 反模式

- **一次性抛出整份待确认清单**（writer + reader + 区划 + enum + 行数同条消息）
- **用户未回复当前轮次就进入下一轮**或提前输出完整 YAML
- **将 Job YAML 写入项目目录**（用户未明确要求保存时）
- **未确认就填写 writer/reader 连接或内联 JDBC**
- **臆造行政区划**（如默认 `440113`、`440115`）或从其他 Job 复制地区相关 `literal`/`areaCode`/编号前缀
- **臆造 seeds 采样 SQL** 或假设维表名/字段名存在
- 用 `regex` 批量生成唯一主键（易碰撞；用 `sequence`/`uuid`）
- 在 `expression` 中对字段加 `#` 前缀（SpEL 根对象为字段 Map，直接用字段名）
- 旧版 `schema.seed` + `mutate`（已移除；用 Job 级 `seeds`）
- 旧版顶层 `job` 字段（用 `id` + `name`）
- 未读现有 Job 就重写一套完全不同的 YAML 风格
