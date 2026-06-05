# Data Generator

基于 YAML 配置的测试数据自动生成 REST 服务。通过 Schema、生成策略与约束规则定义业务数据，支持 PostgreSQL、ClickHouse、CSV 等异构数据源读写，适用于自动化测试造数与开发/联调环境填充。

**技术栈：** Java 21 · Spring Boot 3.3 · Maven 多模块

## 模块结构

```
data-generator/          # 父 POM
├── dg-spi/              # 插件契约（Reader/Writer、Generator、Constraint 接口与公共模型）
├── dg-core/             # 核心引擎（YAML 解析、生成策略、约束引擎、DAG 编排）
├── dg-plugins/          # 数据源插件（PostgreSQL / ClickHouse / CSV）
├── dg-api/              # REST 层（Controller、DTO、任务调度）
└── dg-app/              # Spring Boot 启动入口与配置装配
```

依赖关系：`dg-app → dg-api → dg-core → dg-spi`，`dg-plugins → dg-spi`。

## 快速开始

### 前置条件

- JDK 21+
- Maven 3.8+

### 构建与运行

```bash
# 打包可执行 fat jar
mvn -pl dg-app package -DskipTests

# 在项目根目录启动（默认读取 ./configs）
java -jar dg-app/target/dg-app-0.1.0-SNAPSHOT.jar
```

服务默认监听 `http://localhost:8080`。可通过环境变量或 `application.yml` 覆盖 `data-generator.config-dir` 等配置。

### 运行测试

```bash
mvn clean test
```

> **PostgreSQL / ClickHouse 集成测试** 使用 Testcontainers，需要本地 **Docker** 可用。未安装 Docker 时，对应集成测试会被跳过（`@Disabled("Requires Docker")`），不影响其余单元测试与端到端测试通过。

## 配置目录

YAML 配置默认位于项目根目录 `configs/`（可通过 `data-generator.config-dir` 修改）：

```
configs/
├── schemas/           # 表/数据集 Schema 定义
│   ├── customer.yaml
│   ├── order.yaml
│   └── order_item.yaml
├── references/        # 参考数据（维表）读取配置
│   └── region_lookup.yaml
├── constraints/       # 可复用约束规则集
│   └── order_rules.yaml
└── jobs/              # 多表编排任务（DAG）
    ├── single_customer.yaml
    └── ecommerce_seed.yaml
```

应用级连接与任务参数在 `dg-app/src/main/resources/application.yml` 中定义，Schema/Job YAML 通过 `connection: dev-pg` 等形式引用，避免硬编码凭证。

## REST API 示例

所有接口前缀为 `/api/v1`。

### 健康检查

```bash
curl http://localhost:8080/api/v1/health
```

```json
{ "status": "UP" }
```

### 列出 Schema

```bash
curl http://localhost:8080/api/v1/schemas
```

```json
["customer", "order", "order_item"]
```

### 查看 Schema 详情

```bash
curl http://localhost:8080/api/v1/schemas/customer
```

### 预览生成（不写库）

```bash
curl -X POST http://localhost:8080/api/v1/preview \
  -H "Content-Type: application/json" \
  -d '{
    "jobConfig": "jobs/single_customer.yaml",
    "overrides": { "tables.customers.count": 5 },
    "preview": { "limit": 5 }
  }'
```

响应包含 `status`、`progress` 及 `rows`（各表样本数据），不会写入任何数据源。

### 提交生成任务

```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "jobConfig": "jobs/single_customer.yaml",
    "overrides": { "tables.customers.count": 100 },
    "writer": {
      "type": "csv",
      "connection": "local-csv",
      "mode": "insert"
    }
  }'
```

写入 PostgreSQL 时将 `writer.type` 改为 `postgresql`，`connection` 指向 `application.yml` 中已配置的连接名（如 `dev-pg`）。

### 查询任务状态

```bash
curl http://localhost:8080/api/v1/jobs/{jobId}
```

## P1 能力边界

P1 阶段已交付：

| 能力 | 说明 |
|------|------|
| 五模块骨架 | `dg-spi` / `dg-core` / `dg-plugins` / `dg-api` / `dg-app` |
| 数据源插件 | PostgreSQL、ClickHouse、CSV 读写 |
| 生成策略 | sequence、random、enum、regex、reference（维表引用） |
| 约束引擎 | 字段级（range、nullable、foreign_key）；组合级 SpEL（conditional、mutex） |
| 多表编排 | 单表快捷 Job + 多表 DAG（`depends_on` 拓扑排序） |
| REST API | health、schemas、preview、jobs（同步 + 异步） |

## P2 能力（当前）

| 能力 | 说明 |
|------|------|
| 采样分布 | reference 策略支持 `uniform` / `histogram` / `normal` 分布 |
| 异步任务 | 预估行数 > `syncThreshold` 时返回 **202 Accepted**，轮询 `GET /jobs/{id}` |
| Aviator 表达式 | `level: custom` 或 `language: aviator` 约束 |
| 空间约束 | JTS `within` 拓扑校验（点位于参考几何体内） |

P2 **尚未** 包含（规划于 P3）：

- 任务取消（`DELETE /jobs/{id}`）
- 种子模板、Groovy 自定义插件、约束 `repair` 策略

## P1 能力（已完成）

P1 阶段已交付：

| 能力 | 说明 |
|------|------|
| 五模块骨架 | `dg-spi` / `dg-core` / `dg-plugins` / `dg-api` / `dg-app` |
| 数据源插件 | PostgreSQL、ClickHouse、CSV 读写 |
| 生成策略 | sequence、random、enum、regex、reference（维表引用） |
| 约束引擎 | 字段级（range、nullable、foreign_key）；组合级 SpEL（conditional、mutex） |
| 多表编排 | 单表快捷 Job + 多表 DAG（`depends_on` 拓扑排序） |
| REST API | health、schemas、preview、jobs |

## 许可证

内部项目，版本 `0.1.0-SNAPSHOT`。
