# Job 配置参考

策略与约束的速查表。权威说明见 `dg-web/src/main/resources/static/docs/config-guide.md`。

> **交付：** 生成 Job 时仅在对话中输出 YAML，不写入项目目录；用户自行复制保存。
>
> **确认：** 分轮逐步向用户确认，禁止一次性问完所有待填项。

## 顶层结构

```yaml
id: job_id              # 内置 Job 必填；控制台自定义省略
name: 任务描述           # 内置可选；控制台用 UI「任务名称」
writer: { ... }         # 单写
# writers: [ ... ]      # 多写，与 writer 二选一
seeds: [ ... ]          # 可选，Job 级
schedule:               # 仅内置 Job
  enabled: true
  cron: "0 0 2 * * ?"
tables: [ ... ]         # 必填
```

## 生成策略速查

| 需求 | strategy | 示例 |
|------|----------|------|
| 自增编号 | `sequence` | `{ strategy: sequence, start: 1, step: 1, prefix: 'ORD', width: 6 }` |
| UUID | `uuid` | `{ strategy: uuid }` / `{ strategy: uuid, dashed: false }` |
| 随机整数 | `random` | `{ strategy: random, type: int, min: 0, max: 100 }` |
| 随机时间 | `random` | `{ strategy: random, type: datetime, min: '2024-01-01 00:00:00', max: '2024-12-31 23:59:59' }` |
| 枚举/加权 | `enum` | `{ strategy: enum, values: [A, A, B] }` |
| 常量 | `literal` | `{ strategy: literal, value: '<用户确认的值>' }` |
| 格式串 | `regex` | `{ strategy: regex, pattern: '440115[0-9]{8}' }` |
| 手机号 | `phone` | `{ strategy: phone, region: cn }` |
| 邮箱 | `email` | `{ strategy: email, domain: example.com }` |
| 身份证 | `idcard` | `{ strategy: idcard, areaCode: '<用户确认的6位区划码>' }` |
| 身份证派生 | `idcard` | `{ strategy: idcard, from: sfzmhm, part: gender }` |
| 引用上游表 | `reference` | `{ strategy: reference, source: orders, field: id }` |
| seeds 采样 | `seed` | `{ strategy: seed, source: dim_sample, field: col, default: '' }` |
| 表达式 | `expression` | `{ strategy: expression, expression: "a * b", language: spel }` |

### 通用 generator 参数

| 参数 | 说明 |
|------|------|
| `default` | `null` 或 `''` 时的兜底 |
| `prefix` | 字符串前缀（type 须为字符串） |
| `width` | 数字零填充宽度 |

处理顺序：strategy → default → prefix/width。

### idcard part 值

| part | 输出 |
|------|------|
| `full`（默认） | 完整 18 位 |
| `gender` | `'1'` 男 / `'2'` 女 |
| `age` | 近似年龄（可设 `baseYear`） |
| `birth_date` | `yyyy-MM-dd` |

## seeds

| 字段 | 说明 |
|------|------|
| `name` | Job 内唯一，供 `seed.source` 引用 |
| `reader` | SQL 采样（`type` + `connection`/`url` + `query`） |
| `reference` | 引用 `references/*.yaml` |
| `template` | 内联固定 Map |
| `link` | 关联父 seed：`seed`、`parent_field`（推荐） |

占位符：`:link_id`、`:link.<column>`。

## 表任务

```yaml
- name: logical_name      # 逻辑名，reference/depends_on 用此名
  count: 1000
  depends_on: [upstream]  # 可选
  writer: { ... }         # 可选，覆盖 Job 级
  writers: [ ... ]          # 可选
  schema:
    table: physical_table
    fields: [ ... ]
  constraints: [ ... ]    # 可选
```

## 约束

```yaml
constraints:
  - level: field
    field: amount
    type: range
    min: 0.01
    max: 99999.99
    on_fail: reject       # reject | repair | warn

  - level: field
    field: order_id
    type: foreign_key
    ref_table: orders
    ref_field: id

  - level: composite
    type: conditional
    expression: "amount > 0"
    language: spel
```

## 连接方式

**命名连接（推荐）：**

```yaml
writer:
  type: postgresql
  connection: dev-safety
  mode: insert
```

**内联：**

```yaml
writer:
  type: postgresql
  url: jdbc:postgresql://host:5432/db
  username: postgres
  password: secret
  mode: insert
```

**命名 + 覆盖 url：**

```yaml
writer:
  type: postgresql
  connection: dev-safety
  url: jdbc:postgresql://other:5432/other
  mode: insert
```

优先级：writer 直接字段 > 内联 connection 对象 > 命名连接（通过 `listConnections` 查询可用名）。

## 连接注册表

运行时通过 **`listConnections`** 获取已注册连接名与类型（不含 url/密码）。典型配置示例：

```yaml
data-generator:
  connections:
    dev-safety:   { type: postgresql, url: ..., username: ..., password: ... }
    dev-road:     { type: postgresql, ... }
    dev-ch:       { type: clickhouse, url: ... }
    local-csv:    { type: csv, path: ./output }
    traffic-output: { type: csv, path: ./output/traffic }
```

## 项目常见模式

> **注意：** 下列模式仅说明结构；`reader`/`writer` 连接、采样 SQL、`areaCode`、区划 `literal`、枚举值等**须与用户确认**，勿从范例 Job 直接复制。

### 1. 路网 + 派出所 seeds（警情/驾驶证类）

- 根 seed：路网/路口 UNION + 警格关联（**SQL 与 reader 连接须用户确认**）
- 从属 seed：`link.parent_field: pcsid`（**字段名须与真实维表一致**）
- 事实表字段：`strategy: seed` 绑定地址、经纬度、辖区

结构参考：`city_jq.yaml`、`base_trff_app_drivinglicense.yaml`（本地 configs/jobs 目录）。

### 2. 身份证衍生人物信息

```yaml
- { name: sfzmhm, generator: { strategy: idcard, areaCode: '<用户确认的6位区划码>' } }
- { name: xm, generator: { strategy: enum, values: [<用户确认的姓名列表>] } }
- { name: xb, generator: { strategy: idcard, from: sfzmhm, part: gender } }
- { name: csrq, generator: { strategy: idcard, from: sfzmhm, part: birth_date } }
- { name: dabh, generator: { strategy: idcard, from: sfzmhm } }
```

### 3. PG + ClickHouse 双写

连接名须用户确认，示例结构：

```yaml
writers:
  - { type: postgresql, connection: <用户确认>, mode: insert }
  - { type: clickhouse, connection: <用户确认>, mode: insert }
```

同一批数据 fan-out；结构差异由各自插件映射。

## YAML 陷阱

| 问题 | 处理 |
|------|------|
| `on: id` 被解析为布尔 | 用 `parent_field: id` |
| TIMESTAMP 写入失败 | `random` + `datetime`，VARCHAR 列加 format |
| Unknown connection | 先 `listConnections` 确认连接名，或改用内联 url |
| seed 列为空 | 字段加 `default: ''` |
| writer + writers 并存 | 加载/校验报错，二选一 |

## 表达式语言

`expression` / 约束的 `language`：`spel`（默认）、`aviator`、`groovy`。

SpEL 根对象为当前行字段 Map，直接用字段名，勿加 `#`。
