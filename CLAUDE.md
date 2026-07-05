# CLAUDE.md — Data Generator 开发指南

> **每次会话、每个任务开始前，必须阅读并严格遵循本文档。不得跳过、部分应用或自行降级。**

---

## 0. 强制合规（最高优先级）

在采取任何行动之前，必须：

1. **加载本文档全部规则** — Java 编码规范、Spring Boot 架构规范、CodeGraph 使用策略
2. **遵守全局用户规则** — 包括但不限于：
   - **Git 提交**：仅在用户明确要求时 commit；提交前并行执行 `git status` / `git diff` / `git log`；不擅自 push
   - **代码变更原则**：最小 diff、不过度抽象、匹配现有约定、注释只解释非显而易见逻辑
   - **回复语言**：始终使用**简体中文**与用户沟通
   - **验证**：声称完成/fix/通过前必须运行测试或命令并确认输出
3. **规则冲突时** — 用户当次指令 > 本文档 > 默认行为。若有歧义，先问用户。

---

## 1. 项目概述

**Data Generator** 是基于 YAML 配置的测试数据自动生成 REST 服务。

| 项 | 说明 |
|---|---|
| 语言 / 框架 | Java 21、Spring Boot 3.3、Spring Security |
| 构建 | Maven 多模块 |
| 配置 | YAML（`config-dir` / `writable-config-dir`，Job 由部署方自行维护） |
| 数据源 | PostgreSQL、ClickHouse、CSV（插件化） |
| 任务存储 | SQLite（`./data/dg-jobs.db`，任务记录）+ 文件（`./data/job-logs/`，运行日志） |

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
├── dg-ai/                     # AI Agent 独立 HTTP 服务（AgentScope HarnessAgent）
└── dg-web/                    # Web 应用（Spring Boot 启动、REST API、Web 控制台）
    └── com.datagenerator.web    # controller / service / dto / config / storage
```

### dg-web 包结构

| 包 | 职责 |
|---|---|
| `config/` | `DataGeneratorProperties`、`SecurityConfig`、`DataGeneratorAutoConfiguration` |
| `storage/` | SQLite 任务持久化（`JobRepository`、`JobLogFileRepository`、`JobStartupRecovery`） |
| `controller/` | REST 端点（含 `ConfigController` 连接列表） |
| `proxy/` | `AgentProxyController` — 将 `/api/v1/agent/**` 转发至 dg-ai |
| `security/` | `ServiceAuthFilter` — 校验 `X-DG-Service-Auth` 服务间调用 |
| `service/` | 业务编排（`JobService`、`JobDefinitionService`、`JobLogStore` 等） |
| `dto/` | API 请求/响应模型 |

### dg-ai 包结构

| 包 | 职责 |
|---|---|
| `config/` | `AiAutoConfiguration`（HarnessAgent/Model/Toolkit/StateStore 装配）、`AiProperties` |
| `controller/` | `ChatController` — POST `/api/v1/agent/chat/{chatId}` SSE 端点 |
| `service/` | `AgentService` — HarnessAgent streamEvents → SSE 事件适配（token/verbose 双模式） |
| `tool/` | `ConfigTools`（配置 CRUD/Schema/Connection/校验保存）、`KnowledgeTools`（文档按需检索） |
| `client/` | `DgWebClient` — RestTemplate HTTP 客户端，携带 `X-DG-Service-Auth` 回调 dg-web |
| `prompt/` | `SystemPrompt` — 统一 System Prompt（领域知识 + 工作流程 + 行为规范） |
| `dto/` | `ChatRequest`、`ApiResponse`、`ProviderInfo` |
| `exception/` | `AiExceptionHandler` — @RestControllerAdvice 全局异常处理 |

### 依赖方向

```
dg-web → dg-core → dg-spi
dg-web → dg-plugin-* → dg-spi
dg-ai → dg-web (HTTP 回调 + X-DG-Service-Auth)
```

### 分层约束

| 模块 | 职责 | 禁止 |
|---|---|---|
| `dg-spi` | 接口与公共模型 | 依赖 core/web |
| `dg-core` | 业务引擎，无 Web | 暴露 REST、依赖具体插件实现 |
| `dg-plugin-*` | 单一数据源 Reader/Writer | 依赖 core；各自独立 AutoConfiguration |
| `dg-web` | 启动、REST + DTO + 服务编排、Web UI、配置装配、插件 classpath | 直接返回 core 内部模型；core 业务逻辑 |
| `dg-ai` | 独立 HTTP 服务、Agent ReAct 循环、SSE 流式输出、Tool 回调 dg-web | 依赖 dg-core/dg-plugin；内嵌 agent 业务逻辑 |


---

## 3. CodeGraph 使用策略

本项目配置了 CodeGraph MCP server（`codegraph_*` 工具）。CodeGraph 是 tree-sitter 解析的符号知识图谱，毫秒级返回结构化信息。

**结构性问题优先用 CodeGraph，文本内容问题才用 grep/Read。**

| 问题 | 工具 |
|---|---|
| "X 在哪定义" / "搜索符号 X" | `codegraph_search` |
| "谁调用了 Y" | `codegraph_callers` |
| "Y 调用了谁" | `codegraph_callees` |
| "X 如何到达 Y / 追踪调用链" | `codegraph_trace`（一次调用返回完整路径） |
| "改 Z 会影响什么" | `codegraph_impact` |
| "查看 Y 的签名/源码" | `codegraph_node` |
| "了解某功能区域" | `codegraph_context`（首选，组合 search+node+callers+callees） |
| "查看多个相关符号源码" | `codegraph_explore`（一次调用，优于多次 node/Read） |
| "某目录下有哪些文件" | `codegraph_files` |
| "索引是否健康" | `codegraph_status` |

### 核心规则

- **直接回答，不委托探索。** 对 "how does X work" 类问题，用 2-3 次 codegraph 调用回答：先 `codegraph_context`，再一次 `codegraph_explore` 看源码。对流程问题先 `codegraph_trace` from→to。
- **信任 codegraph 结果。** 来自完整 AST 解析，不要用 grep 重新验证。
- **不要先 grep** 再查符号名 — `codegraph_search` 更快且返回类型+位置+签名。
- **不要链式 `codegraph_search` + `codegraph_node`** — `codegraph_context` 一次搞定。
- **不要循环 `codegraph_node` 遍历多个符号** — 一次 `codegraph_explore` 返回分组源码。
- **索引滞后** — 当 codegraph 响应以 "⚠️ Some files referenced below were edited since the last index sync…" 开头时，列出的文件待重新索引，用 Read 读取这些文件。其他文件 codegraph 是权威来源。

### 如果 `.codegraph/` 不存在

MCP server 返回 "not initialized"。询问用户：*"项目尚未初始化 CodeGraph，需要我运行 `codegraph init -i` 构建索引吗？"*

---

## 4. Java 编码规范

完整规范参考 Google Java Style Guide。以下是必须遵守的核心规则：

### 格式
- **4 空格缩进**，禁用 Tab；续行用 8 空格
- **K&R 大括号**；`if`/`else`/`for`/`while` 即使单行也必须用大括号
- **禁止 wildcard import**（`import java.util.*`）
- 导入顺序：static imports → 空行 → 非 static imports，均按 ASCII 排序
- UTF-8 编码

### 命名
- **包**：全小写，`com.yourcompany.project.module`
- **类/枚举/Record/接口**：`PascalCase`（名词）
- **变量/参数**：`camelCase`
- **常量**：`SCREAMING_SNAKE_CASE`（`static final` 字段）
- **方法**：`camelCase`（动词）
- **布尔变量/方法**：前缀 `is`、`has`、`can`、`should`
- **集合**：复数名（`users`、`messages`）
- **测试方法**：`featureUnderTest_testScenario_expectedBehavior()`

### 最佳实践
- **优先不可变性**：数据载体用 `record`（Java 16+）
- **拥抱函数式**：Stream API + `Optional`，用 `toList()`（Java 16+）代替 `collect(Collectors.toList())`
- **try-with-resources**：所有 `AutoCloseable` 资源必须用
- **高效字符串拼接**：循环中大量拼接用 `StringBuilder`
- **日志性能**：用参数化日志 `log.info("处理: {}", data)`，不用字符串拼接
- **避免可变静态状态**：用依赖注入代替
- **`equals()` 和 `hashCode()`**：必须同时重写，用 `Objects.equals()` / `Objects.hash()`；record 自动处理

---

## 5. Spring Boot 规范

### 代码组织
- **按功能/限界上下文分包**，不按技术层分包：
  ```
  com.myapp.project.user.api/        # Controller、DTO
  com.myapp.project.user.domain/     # Service、领域逻辑
  com.myapp.project.user.infrastructure/  # Repository、外部集成
  ```

### 依赖注入
- **必须用构造函数注入**，禁用 `@Autowired` 字段注入
- 用 Lombok `@RequiredArgsConstructor` 简化

### API 设计
- Controller 只做 HTTP 适配，业务逻辑在 Service
- API 层返回 DTO，不直接返回 core/domain 模型
- 用 `@RestControllerAdvice` 做全局异常处理

### 日志
- 用 SLF4J + Logback，Lombok `@Slf4j`
- 参数化日志，不要字符串拼接
- 异常日志：`log.error("msg: {}", data, e)`（包含异常对象）

### 配置
- 外部化到 `application.yml` 或环境变量
- 用 `@ConfigurationProperties` 实现类型安全配置

### 测试
- **命名**：`feature_scenario_expected`（例：`submitJob_validRequest_delegatesToService`）
- Controller：`@WebMvcTest` + `@MockBean`
- Service：JUnit 5 + Mockito + AssertJ
- Docker 集成测试：`@Disabled("Requires Docker")` + Testcontainers

### 插件注册
- 各 `dg-plugin-*` 通过 `@AutoConfiguration` + `AutoConfiguration.imports` 注册
- **不要**在 `DataGeneratorAutoConfiguration` 中硬编码 `@Import` 所有插件

---

## 6. 构建与验证

```bash
# 全量测试
mvn clean test

# 打包 Web（-am 同时构建 dg-core 与插件）
mvn clean package -pl dg-web -am -DskipTests


# 启动 Web
java -jar dg-web/target/dg-web-0.1.0-SNAPSHOT.jar

```

**部署打包**（Windows）：`.\scripts\package.ps1` 生成 `build/dist/data-generator-<version>.zip`。

**完成任何实现或修复后**，必须运行相关测试并确认通过，再向用户报告成功。

---

## 7. Git 工作流

仅在用户明确要求时执行 commit / push / PR。

### Commit 流程
1. 并行：`git status`、`git diff`、`git log -5`
2. 分析变更，撰写聚焦 **why** 的 commit message
3. `git add` 相关文件 → `git commit`
4. `git status` 确认干净

### Commit 规范
- 使用**中文**提交信息
- 遵循 Conventional Commits（`feat:`、`fix:`、`docs:` 等）
- 描述简洁清晰，动词开头（例如："修复登录超时问题"），不以句号结尾

### 禁止
- 未经请求 commit / push
- `git config` 修改
- `--no-verify`、`--force` 等破坏性操作（除非用户明确要求）
- 提交 `.env`、密钥等敏感文件

---

## 8. 代码注释规范

- 所有代码注释必须使用**简体中文**

---

## 9. 常见任务指引

| 任务 | 做法 |
|---|---|
| 新增数据源插件 | 新建 `dg-plugins/dg-plugin-xxx` 子模块；实现 SPI；独立 AutoConfiguration；在 `dg-web/pom.xml` 按需引入 |
| 新增 REST 端点 | DTO → Service → Controller；补充 `@WebMvcTest` |
| 修改生成/约束逻辑 | 改 `dg-core`；补单元测试 |
| 修改任务持久化 | 改 `dg-web/.../storage/`（`JobRepository`、`JobLogFileRepository`） |
| 修改认证/登录 | 改 `SecurityConfig` + `DataGeneratorProperties.AuthProperties`；静态页 `static/login.html` |
| 修改部署/启停脚本 | 改 `scripts/linux`、`scripts/windows`；打包逻辑在 `scripts/package.ps1` |
| 修改 AI Agent | 改 `dg-ai`（Tool、SystemPrompt、AiAutoConfiguration）；如需新 Tool，实现并注册到 Toolkit |
| 排查调用链 | `codegraph_trace` → `codegraph_explore` |

---

## 10. 文档索引

| 文件 | 用途 |
|---|---|
| `README.md` | 快速开始与能力说明 |
| `CLAUDE.md` | 本文档 — 开发规范与合规要求 |
| `AGENTS.md` | Cursor 代理指南（原始版本，保留参考） |
| `.cursor/rules/*.mdc` | Cursor 项目规则（原始版本，保留参考） |
| `docs/superpowers/specs/` | 产品设计规格 |
| `docs/superpowers/plans/` | 实现计划 |
| `dg-ai/README.md` | dg-ai 模块说明（架构、配置、API、Tool Set） |
| `dg-web/src/main/resources/static/docs/config-guide.md` | Web 控制台配置指南（用户文档） |

---

## 11. 能力阶段

| 阶段 | 能力 |
|---|---|
| P1 | PG/CH/CSV、Schema 驱动生成、SpEL 约束、DAG 编排、同步 Job |
| P2 | histogram/normal 分布、异步 202、Aviator、JTS within |
| P3 | Job 级 seeds、Groovy、repair/warn 约束、DELETE 取消任务、**writers 多写** |
| Web/运维 | Web 控制台、Cron 调度、表单登录、SQLite 任务记录 + 文件运行日志、Job 定义 CRUD |

---

**再次提醒：任何会话的首个动作应是确认已读取并将在整个任务过程中持续遵循本文档的所有规则。违反规则的工作成果视为未完成。**