# AI Agent Job 生成设计规格

**日期：** 2026-06-22  
**状态：** 已实现（独立 HTTP 部署）  
**版本：** 1.1

> **实现说明（2026-06）：** 首版即采用 **`dg-ai` 独立进程 + HTTP**（默认端口 8081），`dg-web` 通过 `AgentProxyController` 转发 `/api/v1/agent/**`，**无 Maven 依赖**。dg-ai Tool 经 `X-DG-Service-Auth` 回调 dg-web 既有 REST API（非独立 agent-tools 端点）。打包脚本 `package.ps1` 同时产出两个 jar；启停脚本支持 `start-all` / `start-ai`。

---

## 1. 概述

### 1.1 目标

为 Data Generator Web 控制台引入 **AI Agent 能力**：用户通过 **悬浮球** 进入对话面板，选择 **Skill**（首版 `generate-job`），以 **多轮对话** 方式生成 Job YAML，校验通过后自动 **打开「新建任务」模态框并填入 YAML**，最终仍由用户核对并通过现有 Job CRUD API 保存。

### 1.2 背景

当前实现：

- Web 控制台（`index.html` + `app.js`）支持 Job 定义 CRUD，YAML 需手工编写
- Cursor Skill `.cursor/skills/generate-job/` 已定义多轮确认流程（writer、seeds、区划、enum 等），禁止臆造连接与地区信息
- 后端无 LLM / Agent 相关代码
- `application.yml` 仅有连接、认证、存储等配置

### 1.3 已确认决策

| 决策项 | 选择 |
|--------|------|
| Agent 框架 | **LangChain4j**（Java） |
| 模块划分 | 新增 **`dg-ai`** 模块；**`dg-web` 只负责调用**（REST/SSE 代理 + Port 适配器） |
| 交互模式 | **多轮对话**，行为对齐 `generate-job` Skill（一轮一问，禁止臆造） |
| LLM | **多 Provider 可切换**（`application.yml` 配置，会话级绑定 provider） |
| Agent 工具 | 经 Port 回调 dg-web：`listConnections`、`listJobDefinitions`、`getJobYaml`、`validateJobYaml` / `validateDraftJobYaml`；可选 `createJobDefinition` / `saveDraftJobDefinition` |
| 保存 Job | 默认由用户在前端核对后保存；Agent 亦可通过 Tool 调用 `POST /api/v1/job-definitions` |
| 部署形态 | **`dg-ai` 独立 HTTP 服务**（默认 8081）；`dg-web` HTTP 代理 + `data-generator.service-auth.token` 服务间认证 |
| 前端入口 | **右下角悬浮球（FAB）** → **右侧抽屉** 对话面板 |
| Artifact 交付 | 校验通过后 SSE `artifact` 事件；前端 **自动打开「新建任务」并写入 YAML** |

### 1.4 非目标（P1）

- 对话内一键保存 Job（`saveJobDefinition` Tool）
- 向量 RAG / 向量库
- 未登录公开 Agent API
- `readTableSchema`、JDBC 读表结构 Tool
- `ai-standalone` 完整 remote 链路（P1 仅骨架 + 文档）
- 配置指南页（`docs.html`）悬浮球（首版仅 `index.html`）
- E2E 依赖真实 LLM 的自动化测试

---

## 2. 架构

### 2.1 方案选择

采用 **方案 1：瘦 Web + 胖 AI**。

| 方案 | 说明 | 结论 |
|------|------|------|
| 1. 瘦 Web + 胖 AI | `dg-ai` 含 LangChain4j、Skill、会话、Tool；`dg-web` 实现 Port 适配器 | **选用** |
| 2. Web 编排 + AI 仅 LLM | Agent 逻辑留在 `dg-web` | 不利于模块抽出 |
| 3. 首日 HTTP 抽象 | 同 JVM 也走 HTTP | P1 复杂度过高 |

### 2.2 模块结构

```
data-generator/
├── dg-spi              # 不变
├── dg-core             # 小扩展：YamlConfigLoader.loadJobFromContent
├── dg-ai               # 新增：AI Agent 引擎
├── dg-plugins/...
└── dg-web              # HTTP 代理 /api/v1/agent；无 dg-ai Maven 依赖
```

**`dg-ai` 包分层：**

| 包 | 职责 | 禁止 |
|----|------|------|
| `...ai.api` | `AgentSessionService`、`SkillRegistry`、DTO | 依赖 `dg-web` |
| `...ai.agent` | LangChain4j Agent、Tool 定义 | 直接读 `application.yml` 连接 |
| `...ai.skill` | 加载 `classpath:skills/**/SKILL.md` | 写 Job 文件 |
| `...ai.config` | 多 Provider `ChatModel` 工厂 | — |
| `...ai.port` | Port 接口声明 | 实现类 |
| `...ai.web`（可选） | REST Controller，`@Profile("ai-standalone")` | 业务逻辑 |

### 2.3 Port 接口（模块解耦关键）

`dg-ai` 只声明 Port，**实现放在 `dg-web`**：

```java
public interface ConnectionCatalogPort {
    List<ConnectionInfo> listConnections();  // 仅 name + type
}
public interface JobCatalogPort {
    List<JobSummary> listJobs();             // id, name, fileName
}
public interface JobValidationPort {
    ValidationResult validateYaml(String yaml);
}
```

LangChain4j `@Tool` 在 `dg-ai` 内注册，内部调用上述 Port。将来 `dg-ai` 独立部署时，Port 可改为 HTTP Client 调 `dg-web` 内部只读 API。

### 2.4 依赖方向

```
dg-web → dg-ai → dg-core（校验 YAML）
dg-ai ↛ dg-web
dg-core ↛ dg-ai
```

### 2.5 调用关系

```mermaid
flowchart LR
    subgraph UI["Web 控制台"]
        FAB["悬浮球 FAB"]
        Drawer["AI 抽屉"]
        Modal["新建任务 Modal"]
    end

    subgraph Web["dg-web"]
        AC["AgentController"]
        AD["Port Adapters"]
        JDS["JobDefinitionService"]
        CR["ConnectionRegistry"]
    end

    subgraph AI["dg-ai"]
        ASS["AgentSessionService"]
        AG["LangChain4j Agent"]
        SK["SkillRegistry"]
        TOOL["Tools → Port"]
    end

    FAB --> Drawer
    Drawer -->|SSE| AC
    AC --> ASS
    ASS --> AG
    AG --> SK
    AG --> TOOL
    TOOL --> AD
    AD --> JDS
    AD --> CR
    Drawer -->|artifact| Modal
```

### 2.6 部署模式

| 模式 | 配置 | 行为 |
|------|------|------|
| **嵌入（默认）** | `data-generator.ai.mode=embedded` | `dg-web` 同 JVM 注入 `AgentSessionService` |
| **远程（预留）** | `mode=remote` + `base-url` | `dg-web` HTTP/SSE 代理至独立 `dg-ai` 进程 |
| **独立启动（预留）** | `spring.profiles.active=ai-standalone` | `dg-ai` 暴露与嵌入模式相同的 REST 契约 |

REST 契约在 `dg-ai` 的 `api`/`dto` 包中**只定义一份**，嵌入/远程共用。

---

## 3. API 与会话

### 3.1 端点（`dg-web` 暴露，`/api/v1/agent`）

所有接口走现有 Session 登录鉴权。

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/skills` | 可选 Skill 列表 |
| `POST` | `/sessions` | 创建会话（skillId + 可选 provider） |
| `GET` | `/sessions/{sessionId}` | 会话元数据 + 消息历史 |
| `POST` | `/sessions/{sessionId}/messages` | 发送用户消息；**响应 SSE 流** |
| `DELETE` | `/sessions/{sessionId}` | 结束会话 |

### 3.2 创建会话

```json
POST /api/v1/agent/sessions
{
  "skillId": "generate-job",
  "provider": "deepseek"
}
→ 201
{
  "sessionId": "uuid",
  "skillId": "generate-job",
  "provider": "deepseek",
  "createdAt": "..."
}
```

`provider` 可选；缺省使用 `data-generator.ai.default-provider`。会话创建后 **不可切换 provider**。

### 3.3 发送消息（SSE）

```json
POST /api/v1/agent/sessions/{sessionId}/messages
{ "content": "我要造一张订单表，写入 dev-safety" }
Accept: text/event-stream
```

| event | data | 用途 |
|-------|------|------|
| `token` | `{"delta":"..."}` | 流式文本 |
| `tool_start` | `{"name":"listConnections"}` | 可选：UI 展示工具调用 |
| `tool_end` | `{"name":"...","ok":true}` | 工具完成 |
| `artifact` | `{"type":"yaml","content":"..."}` | 校验通过的完整 YAML |
| `done` | `{"messageId":"..."}` | 本轮结束 |
| `error` | `{"code":"...","message":"..."}` | 错误 |

### 3.4 Skill 列表

```json
GET /api/v1/agent/skills
→ [
  {
    "id": "generate-job",
    "name": "生成 Job 配置",
    "description": "多轮确认 writer、seeds、区划等，输出 YAML"
  }
]
```

Skill 元数据来自 `classpath:skills/*/SKILL.md` 的 YAML front matter（`name`、`description`）。

### 3.5 会话模型

| 字段 | 说明 |
|------|------|
| `sessionId` | UUID |
| `skillId` | 绑定的 Skill |
| `provider` | 本会话 LLM provider |
| `messages` | LangChain4j `ChatMemory` |
| `draftYaml` | 最近一次 `artifact` 内容（可选缓存） |
| `createdAt` / `lastActiveAt` | TTL 清理依据 |

**P1 存储：进程内 + TTL**

- `ConcurrentHashMap` + 定时清理
- 配置：`data-generator.ai.session.ttl`（默认 2h）、`max-sessions`（默认 100）
- **不写入 SQLite**（Agent 会话为 ephemeral）
- 将来可增 `SessionStore` 接口换 Redis/SQLite，不改 API

**remote 模式注意：** 会话落在 AI 进程，需单实例或会话粘滞（P2 文档化）。

### 3.6 配置示例

```yaml
data-generator:
  ai:
    enabled: true
    mode: embedded
    base-url: http://localhost:8081   # mode=remote 时
    default-provider: deepseek
    chat-memory-max-messages: 40
    request-timeout: 120s
    session:
      ttl: 2h
      max-sessions: 100
    providers:
      deepseek:
        type: open-ai-compatible
        base-url: https://api.deepseek.com/v1
        api-key: ${DEEPSEEK_API_KEY}
        model: deepseek-chat
      openai:
        type: open-ai-compatible
        base-url: https://api.openai.com/v1
        api-key: ${OPENAI_API_KEY}
        model: gpt-4o-mini
      ollama:
        type: ollama
        base-url: http://localhost:11434
        model: qwen2.5:7b
```

`enabled: false` 时 `/api/v1/agent/*` 返回 503，前端隐藏悬浮球。

---

## 4. LangChain4j Agent 与 Skill

### 4.1 技术选型

| 组件 | 选型 |
|------|------|
| Agent 模式 | LangChain4j **AiServices + @Tool** |
| 记忆 | `MessageWindowChatMemory`（上限可配，默认 40 条） |
| 流式 | `StreamingChatModel` + `TokenStream` → SSE `token` |
| Provider 工厂 | `open-ai-compatible` → `OpenAiChatModel`；`ollama` → `OllamaChatModel` |

### 4.2 Skill 资源

```
dg-ai/src/main/resources/skills/
└── generate-job/
    ├── SKILL.md
    └── reference.md
```

与 `.cursor/skills/generate-job/` 内容同步（构建/发布前复制；权威编辑仍在 `.cursor` 目录）。

**`SkillRegistry`：**

1. 启动扫描 `classpath:skills/*/SKILL.md`
2. 解析 front matter → `/skills` 列表
3. 创建会话时：`systemPrompt = SKILL.md + reference.md + 运行时硬约束`

**运行时硬约束（追加至 system prompt）：**

- 控制台场景：YAML **不含** 顶层 `id`、`name`、`schedule`
- 一轮只问 1～2 个相关问题
- 禁止臆造 connection / 区划 / enum
- 完整 YAML 须先调用 `validateJobYaml`，通过后再输出 artifact 标记块

### 4.3 Tool 清单

| Tool | 入参 | 返回 |
|------|------|------|
| `listConnections` | 无 | `[{name, type}]`，**不含** url/凭证 |
| `listJobDefinitions` | 无 | `[{id, name, fileName}]` |
| `validateJobYaml` | `yaml: String` | `{valid, errors[]}` |

### 4.4 YAML 校验路径

`dg-core` 新增：

```java
public JobDefinition loadJobFromContent(String yamlContent)
```

- 解析 YAML 字符串，复用现有 `loadJob` 映射与 `JobSeedValidator`
- `dg-web` 的 `JobValidationPortAdapter` 调用并捕获 `ConfigLoadException`

### 4.5 Artifact 提取

Skill 要求校验通过后，assistant 回复末尾输出：

```
<!-- dg-artifact:yaml -->
... raw yaml ...
<!-- /dg-artifact -->
```

服务端 `ArtifactExtractor` 在流式结束后扫描全文；仅当 `validateJobYaml` 已通过时发送 SSE `artifact`。

### 4.6 单轮执行流程

```mermaid
sequenceDiagram
    participant UI as 前端
    participant Web as dg-web
    participant AI as dg-ai
    participant LLM as ChatModel
    participant Port as Port Adapters

    UI->>Web: POST .../messages
    Web->>AI: sendMessage
    AI->>AI: Skill prompt + ChatMemory
    loop LangChain4j Agent
        LLM-->>AI: 可能 tool call
        AI->>Port: listConnections / listJobs / validateYaml
        Port-->>AI: 结果
        LLM-->>AI: 继续生成
    end
    AI-->>Web: SSE token / artifact / done
    Web-->>UI: text/event-stream
```

---

## 5. 前端

### 5.1 悬浮球 + 抽屉

| 元素 | 说明 |
|------|------|
| **FAB** | 右下角固定，`index.html` 任务管理页可见；`aria-label="AI 生成 Job"` |
| **点击** | 展开右侧抽屉（宽 360–420px） |
| **收起** | 关闭抽屉回到 FAB，**不 DELETE 会话**（除非用户「结束对话」） |
| **再次打开** | 未过期则恢复 sessionId 与历史 |

### 5.2 面板流程

1. 选择 Skill（首版仅 `generate-job`）
2. 可选 Provider → `POST /sessions`
3. 多轮聊天（SSE 流式渲染）
4. 收到 `artifact` → **自动** `openNewDefinitionModal()` → 写入 `#definition-content` → Toast「YAML 已填入，请核对后保存」
5. 用户核对 → 现有「保存」→ `POST /api/v1/job-definitions`

### 5.3 文件变更

| 文件 | 变更 |
|------|------|
| `index.html` | FAB、抽屉 DOM |
| `agent.js`（新建） | SSE、会话状态、`onArtifact` |
| `app.js` | 抽出 `openNewDefinitionModal()` 供复用 |
| `style.css` | `.ai-fab`、`.ai-drawer`；小屏抽屉全屏 overlay |

### 5.4 CSRF

SSE `POST` 沿用现有 CSRF token（`csrf.js`）。

---

## 6. `dg-web` 实现清单

| 类 | 职责 |
|----|------|
| `AgentController` | REST + SSE |
| `EmbeddedAgentClient` | `mode=embedded`，注入 `AgentSessionService` |
| `RemoteAgentClient` | `mode=remote`（P2 完整实现） |
| `ConnectionCatalogPortAdapter` | `ConnectionRegistry` → 脱敏 DTO |
| `JobCatalogPortAdapter` | `JobDefinitionService.list()` |
| `JobValidationPortAdapter` | `YamlConfigLoader.loadJobFromContent` |
| `AgentAutoConfiguration` | Port 注册、条件装配 |

---

## 7. 错误处理与安全

### 7.1 错误处理

| 场景 | 响应 |
|------|------|
| AI 未启用 | 503；悬浮球隐藏 |
| Provider / API Key 缺失 | 503（创建会话时） |
| 会话不存在 / 过期 | 404 |
| 超过 max-sessions | 429 |
| LLM 超时 | SSE `error`，会话保留可重试 |
| YAML 校验失败 | 无 `artifact`，Agent 同会话修正 |
| SSE 断开 | abort TokenStream，无副作用 |

### 7.2 安全

| 项 | 策略 |
|----|------|
| 鉴权 | Session 登录，与 Job API 同级 |
| API Key | 仅服务端，不下发前端 |
| 连接信息 | Tool 仅 name + type |
| Prompt 注入 | Skill 约束 + 只读 Tool |
| 日志 | 不记录 API Key；YAML 日志可截断 |

---

## 8. Maven 变更

父 POM `modules` 增加 `dg-ai`。

```xml
<!-- dg-web -->
<dependency>
  <groupId>com.datagenerator</groupId>
  <artifactId>dg-ai</artifactId>
</dependency>
```

**`dg-ai` 依赖：** `dg-core`、LangChain4j、Spring Boot（optional，standalone 用）。

---

## 9. Phase 划分

| Phase | 范围 |
|-------|------|
| **P1（本 spec）** | embedded 全功能、`generate-job`、3 Tool、FAB+抽屉、SSE、多 Provider、artifact 自动开模态 |
| **P2** | `ai-standalone` + remote Client；Port HTTP 回调；会话 Redis/SQLite |
| **P3** | 新 Skill、`readTableSchema` Tool、RAG reference |

---

## 10. 测试策略

| 层级 | 范围 |
|------|------|
| `dg-core` | `loadJobFromContent` 单元测试 |
| `dg-ai` | `SkillRegistry`、`ArtifactExtractor`、Tool+Mock Port |
| `dg-web` | `AgentController` `@WebMvcTest`；Port Adapter 单元测试 |
| 集成 | 真实 LLM：`@Disabled` + 手工验证清单 |

---

## 11. 验收标准（P1）

1. 登录后可见悬浮球；`ai.enabled=false` 时隐藏
2. 选 `generate-job` + provider，多轮对话可经 Tool 列出连接名
3. 完整 YAML 经 `validateJobYaml` 通过后才发 `artifact`
4. 收到 `artifact` 自动打开「新建任务」并填入 YAML
5. 用户保存后 Job 可经现有流程正常运行
6. `mvn clean test` 通过

---

## 12. 参考

- Cursor Skill：`.cursor/skills/generate-job/SKILL.md`
- 配置指南：`dg-web/src/main/resources/static/docs/config-guide.md`
- Job 定义 CRUD：`JobDefinitionController` / `JobDefinitionService`
- 连接注册：`ConnectionRegistry`、`data-generator.connections`
