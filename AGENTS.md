# AGENTS.md — Data Generator 代理指南

> **每次会话、每个任务开始前，必须先阅读并严格遵循本文档、`.cursor/rules/` 下的项目规则，以及用户在 Cursor 中配置的用户规则（User Rules）。不得跳过、部分应用或自行降级。**

---

## 0. 强制合规（最高优先级）

在采取任何行动（读代码、改代码、提交、回复用户）之前，代理必须：

1. **加载项目规则** — 阅读 `.cursor/rules/` 中所有适用规则：
   - `java.mdc` — Java 21 编码规范（Google Java Style）
   - `springboot.mdc` — Spring Boot 3.x 架构、DI、API、测试规范
   - `codegraph.mdc` — CodeGraph MCP 使用策略（`alwaysApply: true`）

2. **加载用户规则** — 完整遵循 Cursor User Rules，包括但不限于：
   - **Git 提交**：仅在用户明确要求时 commit；提交前并行执行 `git status` / `git diff` / `git log`；使用规范 commit message（PowerShell here-string）；不擅自 push
   - **代码变更原则**：最小 diff、不过度抽象、匹配现有约定、注释只解释非显而易见逻辑
   - **回复语言**：始终使用**中文**与用户沟通
   - **代码引用格式**：使用 ` ```startLine:endLine:filepath ` 格式引用现有代码
   - **验证**：声称完成/fix/通过前必须运行测试或命令并确认输出

3. **规则冲突时** — 用户当次指令 > 用户规则 > 项目规则 > 代理默认行为。若有歧义，先问用户。

4. **每次任务自检清单**（ mentally confirm before finishing）：
   - [ ] 是否遵循了 `java.mdc` / `springboot.mdc`？
   - [ ] 结构性问题是否优先用了 CodeGraph 而非 grep 循环？
   - [ ] API 层是否返回 DTO 而非 core 模型？
   - [ ] 测试方法命名是否为 `feature_scenario_expected`？
   - [ ] 变更范围是否最小、无无关改动？
   - [ ] 若涉及 git：是否按用户 git rules 执行？
   - [ ] 回复是否使用中文？

---

## 1. 项目概述

**Data Generator** 是基于 YAML 配置的测试数据自动生成 REST 服务。

| 项 | 说明 |
|---|---|
| 语言 / 框架 | Java 21、Spring Boot 3.3、Spring Security |
| 构建 | Maven 多模块 |
| 配置 | 本地 YAML（`dg-web/src/main/resources/configs/`） |
| 数据源 | PostgreSQL、ClickHouse、CSV（插件化） |
| 任务存储 | SQLite（`./data/dg-jobs.db`，任务记录 + 运行日志） |

设计文档：
- Spec：`docs/superpowers/specs/2026-06-05-data-generator-design.md`
- Plan：`docs/superpowers/plans/2026-06-05-data-generator.md`
- 任务持久化 Spec：`docs/superpowers/specs/2026-06-05-job-log-sqlite-design.md`
- 任务持久化 Plan：`docs/superpowers/plans/2026-06-05-job-log-sqlite.md`

---

## 2. 模块结构

```
data-generator/
├── dg-spi/                    # 插件契约（Reader/Writer、Generator、Constraint SPI）
├── dg-core/                   # 核心引擎（YAML、生成策略、约束、DAG 编排）
├── dg-plugins/                # 插件聚合 POM
│   ├── dg-plugin-postgresql/
│   ├── dg-plugin-clickhouse/
│   └── dg-plugin-csv/
└── dg-web/                    # Web 应用（Spring Boot 启动、REST API、Web 控制台）
    └── com.datagenerator.web    # controller / service / dto / config / storage
```

`dg-web` 包结构要点：

| 包 | 职责 |
|---|---|
| `config/` | `DataGeneratorProperties`、`SecurityConfig`、`DataGeneratorAutoConfiguration` |
| `storage/` | SQLite 任务持久化（`JobRepository`、`JobLogRepository`、`JobStartupRecovery`） |
| `controller/` | REST 端点 |
| `service/` | 业务编排（`JobService`、`JobDefinitionService`、`JobLogStore` 等） |
| `dto/` | API 请求/响应模型 |

**依赖方向：**

```
dg-web → dg-core → dg-spi
dg-web → dg-plugin-* → dg-spi
```

**分层约束：**

| 模块 | 职责 | 禁止 |
|---|---|---|
| `dg-spi` | 接口与公共模型 | 依赖 core/web |
| `dg-core` | 业务引擎，无 Web | 暴露 REST、依赖具体插件实现 |
| `dg-plugin-*` | 单一数据源 Reader/Writer | 依赖 core；各自独立 AutoConfiguration |
| `dg-web` | 启动、REST + DTO + 服务编排、Web UI、配置装配、插件 classpath | 直接返回 core 内部模型；core 业务逻辑 |

---

## 3. 代码探索（CodeGraph）

结构/调用/影响范围问题 **必须优先使用 CodeGraph MCP**，参见 `.cursor/rules/codegraph.mdc`。

| 意图 | 工具 |
|---|---|
| 某符号在哪定义 | `codegraph_search` |
| 了解某功能区域 | `codegraph_context` |
| X 如何到达 Y | `codegraph_trace` |
| 改 Z 会影响什么 | `codegraph_impact` |
| 查看多个相关符号源码 | `codegraph_explore`（一次调用） |

**反模式：** 对符号名先 grep 再逐个 Read；用 subagent 重复 CodeGraph 已能完成的探索。

---

## 4. 编码规范摘要

完整规范见 `.cursor/rules/java.mdc` 与 `springboot.mdc`。以下为项目级要点：

### Java

- Google Java Style；4 空格缩进；UTF-8；禁止 wildcard import
- 使用 Java 21 特性（records、pattern matching 等，与现有代码一致时）

### Spring Boot

- 构造函数注入，不用 `@Autowired` 字段注入
- Controller 只做 HTTP 适配；业务在 Service；core 不依赖 Spring Web
- 全局异常处理，不向前端泄露内部异常栈
- 插件通过各 `dg-plugin-*` 的 `@AutoConfiguration` + `AutoConfiguration.imports` 注册，**不要**在 `DataGeneratorAutoConfiguration` 中硬编码 `@Import` 所有插件

### 测试

- 命名：`feature_scenario_expected`（例：`submitJob_validRequest_delegatesToService`）
- Controller：`@WebMvcTest` + `@MockBean`
- Service：JUnit 5 + Mockito + AssertJ
- Docker 集成测试：`@Disabled("Requires Docker")` + Testcontainers

### 变更原则

- **最小 diff**：只改任务所需文件
- **匹配现有风格**：读周围代码再写
- **不主动**创建 README/文档，除非用户要求
- **不主动** commit，除非用户要求

---

## 5. 构建与验证

```bash
# 全量测试
mvn clean test

# 打包
mvn -pl dg-web package -DskipTests

# 启动（任意目录均可，默认从 classpath 读取 configs/）
java -jar dg-web/target/dg-web-0.1.0-SNAPSHOT.jar
```

**完成任何实现或修复后**，必须运行相关测试并确认通过，再向用户报告成功。

---

## 6. Git 工作流

仅在用户明确要求时执行 commit / push / PR。

### Commit 流程

1. 并行：`git status`、`git diff`、`git log -5`
2. 分析变更，撰写聚焦 **why** 的 commit message
3. `git add` 相关文件 → `git commit`（PowerShell here-string 多行 message）
4. `git status` 确认干净

### 禁止

- 未经请求 commit / push
- `git config` 修改
- `--no-verify`、`--force` 等破坏性操作（除非用户明确要求）
- 提交 `.env`、密钥等敏感文件

---

## 7. 能力阶段（参考）

| 阶段 | 能力 |
|---|---|
| P1 | PG/CH/CSV、Schema 驱动生成、SpEL 约束、DAG 编排、同步 Job |
| P2 | histogram/normal 分布、异步 202、Aviator、JTS within |
| P3 | 种子模板、Groovy、repair/warn 约束、DELETE 取消任务 |
| Web/运维 | Web 控制台、Cron 调度、表单登录（`data-generator.auth.*`）、SQLite 任务/日志持久化、Job 定义 CRUD |

详见 `README.md`。

---

## 8. 常见任务指引

| 任务 | 做法 |
|---|---|
| 新增数据源插件 | 新建 `dg-plugins/dg-plugin-xxx` 子模块；实现 SPI；独立 AutoConfiguration；在 `dg-web/pom.xml` 按需引入 |
| 新增 REST 端点 | DTO → Service → Controller；补充 `@WebMvcTest`（`@AutoConfigureMockMvc(addFilters = false)` 若不测 Security） |
| 修改生成/约束逻辑 | 改 `dg-core`；补单元测试 |
| 修改任务持久化 | 改 `dg-web/.../storage/`（`JobRepository`、`JobLogRepository`）；规格见 job-log-sqlite spec |
| 修改认证/登录 | 改 `SecurityConfig` + `DataGeneratorProperties.AuthProperties`；静态页 `static/login.html` |
| 排查调用链 | `codegraph_trace` → `codegraph_explore` |

---

## 9. 文档索引

| 文件 | 用途 |
|---|---|
| `README.md` | 快速开始与能力说明 |
| `AGENTS.md` | 本文档 — 代理行为与合规要求 |
| `.cursor/rules/*.mdc` | 项目级编码与工具规则 |
| `docs/superpowers/specs/` | 产品设计规格 |
| `docs/superpowers/plans/` | 实现计划 |
| `docs/superpowers/specs/2026-06-05-job-log-sqlite-design.md` | 任务 SQLite 持久化设计 |
| `docs/superpowers/plans/2026-06-05-job-log-sqlite.md` | 任务 SQLite 持久化实现计划 |

---

**再次提醒：任何代理会话的首个动作之一，应是确认已读取并将在整个任务过程中持续遵循 `.cursor/rules/` 与用户 User Rules。违反规则的工作成果视为未完成。**
