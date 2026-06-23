# AI Agent Job 生成 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 新增 `dg-ai` 模块（LangChain4j Agent + Skill + Tool），`dg-web` 通过 HTTP 代理暴露 `/api/v1/agent/*`；前端悬浮球 + 抽屉多轮对话，artifact 自动打开「新建任务」并填入 YAML。

**Architecture:** `dg-ai` 独立 Spring Boot 进程，承载 `AgentSessionService`、LangChain4j AiServices、`SkillRegistry`、`JobGeneratorTools`（经 HTTP Port 调 dg-web REST）；`dg-core` 新增 `YamlConfigLoader.loadJobFromContent` 供校验。dg-web 配置 `data-generator.ai.remote-base-url` 与 `service-auth.token`；dg-ai 配置 `ai.remote-services.data-generator-web.*` 与 LLM Provider。

**实现状态：** 核心链路已实现（独立部署、FAB、SSE、Skill、Tool 回调、打包脚本）。原计划的同 JVM embedded / Port Adapter 方案已替换为 HTTP 抽象。

**Tech Stack:** Java 21, Spring Boot 3.3, LangChain4j 1.0.0（**编程式 API，不用 spring-boot-starter** 以避免与 SB 3.3 版本冲突）, SnakeYAML, JUnit 5, Mockito, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-22-ai-agent-job-generation-design.md`

---

## File Structure

```
pom.xml                                      # 修改：modules 增加 dg-ai；dependencyManagement 增加 dg-ai、langchain4j

dg-core/
├── src/main/java/com/datagenerator/core/schema/
│   └── YamlConfigLoader.java                # 修改：loadJobFromContent(String)
└── src/test/java/com/datagenerator/core/schema/
    └── YamlConfigLoaderContentTest.java     # 新建：字符串 YAML 解析测试

dg-ai/                                       # 新建模块
├── pom.xml
└── src/main/
    ├── java/com/datagenerator/ai/
    │   ├── api/
    │   │   ├── AgentSessionService.java     # 会话 CRUD + sendMessage(SSE)
    │   │   ├── SkillRegistry.java
    │   │   └── dto/                         # SkillInfo, SessionResponse, SseEvent, ...
    │   ├── port/
    │   │   ├── ConnectionCatalogPort.java
    │   │   ├── JobCatalogPort.java
    │   │   └── JobValidationPort.java
    │   ├── skill/
    │   │   └── SkillDefinition.java
    │   ├── agent/
    │   │   ├── JobGeneratorAgent.java       # LangChain4j AI Service 接口
    │   │   ├── JobGeneratorTools.java       # @Tool 实现
    │   │   ├── ArtifactExtractor.java
    │   │   └── StreamingResponseHandler.java
    │   ├── session/
    │   │   ├── AgentSession.java
    │   │   ├── ChatMemoryStore.java
    │   │   └── InMemoryChatMemoryStore.java
    │   ├── config/
    │   │   ├── AiProperties.java            # data-generator.ai.*
    │   │   ├── ChatModelFactory.java
    │   │   └── AiAutoConfiguration.java
    │   └── service/
    │       └── DefaultAgentSessionService.java
    └── resources/skills/generate-job/
        ├── SKILL.md                         # 从 .cursor/skills/generate-job/ 复制
        └── reference.md
    └── test/java/com/datagenerator/ai/
        ├── skill/SkillRegistryTest.java
        ├── agent/ArtifactExtractorTest.java
        └── service/DefaultAgentSessionServiceTest.java

dg-web/
├── pom.xml                                  # 修改：依赖 dg-ai
├── src/main/java/com/datagenerator/web/
│   ├── config/
│   │   ├── DataGeneratorProperties.java     # 修改：嵌套 AiProperties 或引用 dg-ai 配置
│   │   └── AgentWebConfiguration.java       # 新建：Port Adapter Beans
│   ├── adapter/
│   │   ├── ConnectionCatalogPortAdapter.java
│   │   ├── JobCatalogPortAdapter.java
│   │   └── JobValidationPortAdapter.java
│   ├── controller/
│   │   └── AgentController.java             # 新建：REST + SSE
│   └── exception/
│       └── GlobalExceptionHandler.java      # 修改：Agent 相关异常
├── src/main/resources/
│   ├── application.yml                      # 修改：data-generator.ai 块（enabled: false 默认）
│   └── static/
│       ├── index.html                       # 修改：FAB + 抽屉 DOM；引入 agent.js
│       ├── agent.js                         # 新建：SSE 客户端、会话 UI
│       ├── app.js                           # 修改：导出 openDefinitionModalForAi(yaml)
│       └── style.css                        # 修改：.ai-fab、.ai-drawer
└── src/test/java/com/datagenerator/web/
    ├── adapter/JobValidationPortAdapterTest.java
    └── controller/AgentControllerTest.java
```

---

## Task 1: Maven — 新增 dg-ai 模块

**Files:**
- Modify: `pom.xml`
- Create: `dg-ai/pom.xml`

- [x] **Step 1: 父 POM 增加 module 与 dependencyManagement**

`pom.xml` `<modules>` 增加：

```xml
<module>dg-ai</module>
```

`<properties>` 增加：

```xml
<langchain4j.version>1.0.0</langchain4j.version>
```

`<dependencyManagement>` 增加：

```xml
<dependency>
    <groupId>com.datagenerator</groupId>
    <artifactId>dg-ai</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-ollama</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

- [x] **Step 2: 创建 dg-ai/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.datagenerator</groupId>
        <artifactId>data-generator</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>dg-ai</artifactId>
    <packaging>jar</packaging>
    <name>dg-ai</name>
    <description>Data Generator AI Agent module</description>
    <dependencies>
        <dependency>
            <groupId>com.datagenerator</groupId>
            <artifactId>dg-core</artifactId>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j</artifactId>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-open-ai</artifactId>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-ollama</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [x] **Step 3: dg-web 增加依赖**

`dg-web/pom.xml` `<dependencies>` 增加：

```xml
<dependency>
    <groupId>com.datagenerator</groupId>
    <artifactId>dg-ai</artifactId>
</dependency>
```

- [x] **Step 4: 验证编译**

Run: `mvn -pl dg-ai,dg-web -am compile -q`
Expected: BUILD SUCCESS（此时仅有 pom，空模块也通过）

---

## Task 2: dg-core — loadJobFromContent

**Files:**
- Modify: `dg-core/src/main/java/com/datagenerator/core/schema/YamlConfigLoader.java`
- Create: `dg-core/src/test/java/com/datagenerator/core/schema/YamlConfigLoaderContentTest.java`

- [x] **Step 1: 编写失败测试**

```java
package com.datagenerator.core.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlConfigLoaderContentTest {

    private YamlConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new YamlConfigLoader(ConfigPathResolver.classpathOnly(getClass().getClassLoader()));
    }

    @Test
    void loadJobFromContent_validYaml_parsesTables() {
        String yaml = """
                writer:
                  type: csv
                  connection: local-csv
                tables:
                  - name: t1
                    count: 10
                    schema:
                      table: t1
                      fields:
                        - name: id
                          type: BIGINT
                          generator: { strategy: sequence, start: 1 }
                """;
        JobDefinition job = loader.loadJobFromContent(yaml);
        assertThat(job.getTables()).hasSize(1);
        assertThat(job.getTables().get(0).getName()).isEqualTo("t1");
    }

    @Test
    void loadJobFromContent_missingTables_throws() {
        assertThatThrownBy(() -> loader.loadJobFromContent("writer: { type: csv }"))
                .isInstanceOf(ConfigLoadException.class);
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn -pl dg-core test -Dtest=YamlConfigLoaderContentTest -q`
Expected: FAIL（`loadJobFromContent` 不存在）

- [x] **Step 3: 实现 loadJobFromContent**

在 `YamlConfigLoader.java` 增加：

```java
public JobDefinition loadJobFromContent(String yamlContent) {
    if (yamlContent == null || yamlContent.isBlank()) {
        throw new ConfigLoadException("Empty YAML content");
    }
    try {
        Object loaded = yaml.load(yamlContent);
        if (loaded == null) {
            throw new ConfigLoadException("Empty YAML content");
        }
        return loadJobFromRoot(YamlMappingUtils.asMap(loaded));
    } catch (ConfigLoadException exception) {
        throw exception;
    } catch (Exception exception) {
        throw new ConfigLoadException("Failed to parse YAML content", exception);
    }
}

private JobDefinition loadJobFromRoot(Map<String, Object> root) {
    // 将现有 loadJob(String path) 中 root 映射逻辑提取到此 private 方法
    // loadJob(path) 改为：return loadJobFromRoot(loadYamlMap(path));
}
```

重构 `loadJob(String path)` 调用 `loadJobFromRoot`，避免重复。

- [x] **Step 4: 运行测试确认通过**

Run: `mvn -pl dg-core test -Dtest=YamlConfigLoaderContentTest -q`
Expected: BUILD SUCCESS

---

## Task 3: dg-ai — Port 接口与 DTO

**Files:**
- Create: `dg-ai/src/main/java/com/datagenerator/ai/port/*.java`
- Create: `dg-ai/src/main/java/com/datagenerator/ai/api/dto/*.java`

- [x] **Step 1: 创建 Port 接口**

`ConnectionCatalogPort.java`：

```java
package com.datagenerator.ai.port;

import java.util.List;

public interface ConnectionCatalogPort {
    List<ConnectionInfo> listConnections();

    record ConnectionInfo(String name, String type) {}
}
```

`JobCatalogPort.java`：

```java
package com.datagenerator.ai.port;

import java.util.List;

public interface JobCatalogPort {
    List<JobSummary> listJobs();

    record JobSummary(String id, String name, String fileName) {}
}
```

`JobValidationPort.java`：

```java
package com.datagenerator.ai.port;

import java.util.List;

public interface JobValidationPort {
    ValidationResult validateYaml(String yaml);

    record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }
        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }
}
```

- [x] **Step 2: 创建 API DTO**

`SkillInfo.java`：`id`, `name`, `description`

`CreateSessionRequest.java`：`skillId`, `provider`（可选）

`SessionResponse.java`：`sessionId`, `skillId`, `provider`, `createdAt`, `draftYaml`（可选）

`SendMessageRequest.java`：`content`

`SseEvent.java`：`event`, `data`（JSON 字符串）

- [x] **Step 3: 编译验证**

Run: `mvn -pl dg-ai compile -q`
Expected: BUILD SUCCESS

---

## Task 4: dg-ai — Skill 资源与 SkillRegistry

**Files:**
- Create: `dg-ai/src/main/resources/skills/generate-job/SKILL.md`
- Create: `dg-ai/src/main/resources/skills/generate-job/reference.md`
- Create: `dg-ai/src/main/java/com/datagenerator/ai/skill/SkillDefinition.java`
- Create: `dg-ai/src/main/java/com/datagenerator/ai/api/SkillRegistry.java`
- Create: `dg-ai/src/test/java/com/datagenerator/ai/skill/SkillRegistryTest.java`

- [x] **Step 1: 复制 Skill 文件**

从 `.cursor/skills/generate-job/SKILL.md` 与 `reference.md` 复制到 `dg-ai/src/main/resources/skills/generate-job/`。

- [x] **Step 2: 编写失败测试**

```java
@Test
void listSkills_findsGenerateJob() {
    SkillRegistry registry = new SkillRegistry();
    registry.loadFromClasspath();
    assertThat(registry.list()).extracting(SkillInfo::id)
            .contains("generate-job");
}

@Test
void getSystemPrompt_includesSkillAndReference() {
    SkillRegistry registry = new SkillRegistry();
    registry.loadFromClasspath();
    String prompt = registry.buildSystemPrompt("generate-job");
    assertThat(prompt).contains("逐步确认");
    assertThat(prompt).contains("禁止臆造");
}
```

- [x] **Step 3: 实现 SkillRegistry**

- 扫描 `classpath:skills/*/SKILL.md`
- 解析 front matter（`---` 块内 `name`、`description`）
- `buildSystemPrompt(skillId)` = SKILL 正文 + reference.md + `RUNTIME_CONSTRAINTS` 常量（控制台不含 id/name/schedule 等）

- [x] **Step 4: 运行测试**

Run: `mvn -pl dg-ai test -Dtest=SkillRegistryTest -q`
Expected: BUILD SUCCESS

---

## Task 5: dg-ai — LangChain4j Tools 与 ChatModelFactory

**Files:**
- Create: `dg-ai/src/main/java/com/datagenerator/ai/agent/JobGeneratorTools.java`
- Create: `dg-ai/src/main/java/com/datagenerator/ai/config/AiProperties.java`
- Create: `dg-ai/src/main/java/com/datagenerator/ai/config/ChatModelFactory.java`
- Create: `dg-ai/src/main/java/com/datagenerator/ai/agent/JobGeneratorAgent.java`

- [x] **Step 1: AiProperties**

```java
@ConfigurationProperties(prefix = "data-generator.ai")
public class AiProperties {
    private boolean enabled = false;
    private String mode = "embedded";
    private String defaultProvider = "deepseek";
    private int chatMemoryMaxMessages = 40;
    private Duration requestTimeout = Duration.ofSeconds(120);
    private SessionProperties session = new SessionProperties();
    private Map<String, ProviderProperties> providers = new HashMap<>();
    // getters/setters; SessionProperties: ttl, maxSessions
    // ProviderProperties: type, baseUrl, apiKey, model
}
```

- [x] **Step 2: ChatModelFactory**

按 `providers` 条目创建 `ChatModel` 与 `StreamingChatModel`（Map<String, StreamingChatModel>）：

```java
public StreamingChatModel createStreaming(ProviderProperties props) {
    return switch (props.getType()) {
        case "open-ai-compatible" -> OpenAiStreamingChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModel())
                .timeout(props.getTimeout())
                .build();
        case "ollama" -> OllamaStreamingChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .modelName(props.getModel())
                .timeout(props.getTimeout())
                .build();
        default -> throw new IllegalArgumentException("Unknown provider type: " + props.getType());
    };
}
```

- [x] **Step 3: JobGeneratorTools**

```java
public class JobGeneratorTools {
    private final ConnectionCatalogPort connections;
    private final JobCatalogPort jobs;
    private final JobValidationPort validation;

    @Tool("列出可用的数据连接名称与类型，不含 url 和密码")
    public List<ConnectionCatalogPort.ConnectionInfo> listConnections() {
        return connections.listConnections();
    }

    @Tool("列出已有 Job 定义的 id、名称与文件名")
    public List<JobCatalogPort.JobSummary> listJobDefinitions() {
        return jobs.listJobs();
    }

    @Tool("校验 Job YAML 是否符合 Data Generator 配置规范")
    public JobValidationPort.ValidationResult validateJobYaml(String yaml) {
        return validation.validateYaml(yaml);
    }
}
```

- [x] **Step 4: JobGeneratorAgent 接口**

```java
public interface JobGeneratorAgent {
    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
```

- [x] **Step 5: 编译**

Run: `mvn -pl dg-ai compile -q`
Expected: BUILD SUCCESS

---

## Task 6: dg-ai — 会话存储、ArtifactExtractor、AgentSessionService

**Files:**
- Create: `dg-ai/src/main/java/com/datagenerator/ai/session/*.java`
- Create: `dg-ai/src/main/java/com/datagenerator/ai/agent/ArtifactExtractor.java`
- Create: `dg-ai/src/main/java/com/datagenerator/ai/service/DefaultAgentSessionService.java`
- Create: `dg-ai/src/main/java/com/datagenerator/ai/config/AiAutoConfiguration.java`
- Create: `dg-ai/src/test/java/com/datagenerator/ai/agent/ArtifactExtractorTest.java`

- [x] **Step 1: ArtifactExtractor 测试**

```java
@Test
void extractYaml_findsMarkedBlock() {
    String text = "说明\n<!-- dg-artifact:yaml -->\nwriter:\n  type: csv\n<!-- /dg-artifact -->";
    assertThat(ArtifactExtractor.extractYaml(text)).contains("type: csv");
}

@Test
void extractYaml_noMarker_returnsEmpty() {
    assertThat(ArtifactExtractor.extractYaml("no yaml")).isEmpty();
}
```

- [x] **Step 2: 实现 InMemoryChatMemoryStore + AgentSession**

- `AgentSession`：sessionId, skillId, provider, createdAt, lastActiveAt, draftYaml
- `InMemoryChatMemoryStore`：`ConcurrentHashMap` + `MessageWindowChatMemory`
- TTL 清理：`@Scheduled` 或 lazy check on access

- [x] **Step 3: DefaultAgentSessionService**

核心方法：

```java
public SessionResponse createSession(CreateSessionRequest request);
public SessionResponse getSession(String sessionId);
public void deleteSession(String sessionId);
public void sendMessage(String sessionId, String content, Consumer<SseEvent> emitter);
```

`sendMessage` 流程：

1. 获取/创建 `JobGeneratorAgent`（AiServices.builder + StreamingChatModel + tools + systemMessage + chatMemory）
2. `agent.chat(sessionId, content)` 返回 `TokenStream`
3. `onPartialResponse` → emit `token`
4. `onComplete` → `ArtifactExtractor.extractYaml`；若 non-empty 且 `validation.validateYaml(yaml).valid()` → emit `artifact`；更新 `draftYaml`
5. emit `done`

- [x] **Step 4: AiAutoConfiguration**

```java
@Configuration
@EnableConfigurationProperties(AiProperties.class)
@ConditionalOnProperty(prefix = "data-generator.ai", name = "enabled", havingValue = "true")
public class AiAutoConfiguration {
    @Bean
    SkillRegistry skillRegistry() { ... }

    @Bean
    AgentSessionService agentSessionService(...) { ... }
}
```

Port 由 `dg-web` 注入（`@Autowired` 构造参数）。

- [x] **Step 5: 运行测试**

Run: `mvn -pl dg-ai test -Dtest=ArtifactExtractorTest -q`
Expected: BUILD SUCCESS

---

## Task 7: dg-web — Port Adapters

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/adapter/ConnectionCatalogPortAdapter.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/adapter/JobCatalogPortAdapter.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/adapter/JobValidationPortAdapter.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/config/AgentWebConfiguration.java`
- Create: `dg-web/src/test/java/com/datagenerator/web/adapter/JobValidationPortAdapterTest.java`

- [x] **Step 1: ConnectionCatalogPortAdapter**

```java
@Component
public class ConnectionCatalogPortAdapter implements ConnectionCatalogPort {
    private final DataGeneratorProperties properties;

    @Override
    public List<ConnectionInfo> listConnections() {
        return properties.getConnections().entrySet().stream()
                .map(e -> new ConnectionInfo(
                        e.getKey(),
                        String.valueOf(e.getValue().getOrDefault("type", "unknown"))))
                .sorted(Comparator.comparing(ConnectionInfo::name))
                .toList();
    }
}
```

- [x] **Step 2: JobCatalogPortAdapter**

委托 `JobDefinitionService.list()` → `JobSummary(id, name, fileName)`

- [x] **Step 3: JobValidationPortAdapter 测试 + 实现**

```java
@Test
void validateYaml_invalid_returnsErrors() {
    ValidationResult result = adapter.validateYaml("tables: []");
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).isNotEmpty();
}
```

实现调用 `yamlConfigLoader.loadJobFromContent(yaml)`，捕获 `ConfigLoadException` → `ValidationResult.fail(List.of(message))`

- [x] **Step 4: AgentWebConfiguration**

注册 3 个 Port Adapter Bean；`@Import(AiAutoConfiguration.class)` 或在 `DataGeneratorAutoConfiguration` 中 `@Import`

- [x] **Step 5: 运行测试**

Run: `mvn -pl dg-web test -Dtest=JobValidationPortAdapterTest -q`
Expected: BUILD SUCCESS

---

## Task 8: dg-web — AgentController（REST + SSE）

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/controller/AgentController.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/exception/GlobalExceptionHandler.java`
- Create: `dg-web/src/test/java/com/datagenerator/web/controller/AgentControllerTest.java`

- [x] **Step 1: 编写失败 WebMvcTest**

```java
@WebMvcTest(controllers = AgentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AgentSessionService agentSessionService;

    @Test
    void listSkills_delegatesToService() throws Exception {
        when(agentSessionService.listSkills()).thenReturn(List.of(
                new SkillInfo("generate-job", "生成 Job", "desc")));
        mockMvc.perform(get("/api/v1/agent/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("generate-job"));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -pl dg-web test -Dtest=AgentControllerTest -q`
Expected: FAIL

- [x] **Step 3: 实现 AgentController**

```java
@RestController
@RequestMapping("/api/v1/agent")
@ConditionalOnProperty(prefix = "data-generator.ai", name = "enabled", havingValue = "true")
public class AgentController {

    private final AgentSessionService agentSessionService;

    @GetMapping("/skills")
    public List<SkillInfo> listSkills() {
        return agentSessionService.listSkills();
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(agentSessionService.createSession(request));
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionResponse getSession(@PathVariable String sessionId) {
        return agentSessionService.getSession(sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String sessionId) {
        agentSessionService.deleteSession(sessionId);
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request) {
        SseEmitter emitter = new SseEmitter(aiProperties.getRequestTimeout().toMillis());
        agentSessionService.sendMessage(sessionId, request.content(), event -> {
            try {
                emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
            } catch (IOException ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }
}
```

`GlobalExceptionHandler` 增加：`SessionNotFoundException` → 404；`AiDisabledException` / 无 provider → 503。

- [x] **Step 4: application.yml 增加配置（默认 disabled）**

```yaml
data-generator:
  ai:
    enabled: false
    default-provider: deepseek
    providers:
      deepseek:
        type: open-ai-compatible
        base-url: https://api.deepseek.com/v1
        api-key: ${DEEPSEEK_API_KEY:}
        model: deepseek-chat
```

- [x] **Step 5: 运行测试**

Run: `mvn -pl dg-web test -Dtest=AgentControllerTest -q`
Expected: BUILD SUCCESS

---

## Task 9: 前端 — 悬浮球 + agent.js

**Files:**
- Modify: `dg-web/src/main/resources/static/index.html`
- Create: `dg-web/src/main/resources/static/agent.js`
- Modify: `dg-web/src/main/resources/static/app.js`
- Modify: `dg-web/src/main/resources/static/style.css`

- [x] **Step 1: index.html 增加 DOM**

在 `</body>` 前、`app.js` 之前：

```html
<button id="ai-fab" class="ai-fab hidden" type="button" aria-label="AI 生成 Job">AI</button>
<aside id="ai-drawer" class="ai-drawer hidden" aria-label="AI 助手">
    <header class="ai-drawer-header">
        <h2>AI 生成 Job</h2>
        <button id="ai-drawer-close" type="button" aria-label="关闭">&times;</button>
    </header>
    <div class="ai-drawer-body">
        <label class="field">
            <span>Skill</span>
            <select id="ai-skill-select"></select>
        </label>
        <label class="field">
            <span>模型</span>
            <select id="ai-provider-select"></select>
        </label>
        <div id="ai-messages" class="ai-messages"></div>
        <form id="ai-chat-form" class="ai-chat-form">
            <textarea id="ai-input" rows="2" placeholder="描述你要生成的 Job…"></textarea>
            <button type="submit" class="btn primary">发送</button>
        </form>
        <button id="ai-end-session" type="button" class="btn">结束对话</button>
    </div>
</aside>
<script src="/agent.js"></script>
```

- [x] **Step 2: app.js 导出 openDefinitionModalForAi**

```javascript
function openDefinitionModalForAi(yaml) {
    openDefinitionModal(null, yaml, false, null, null);
}
window.openDefinitionModalForAi = openDefinitionModalForAi;
```

- [x] **Step 3: agent.js 核心逻辑**

- 启动时 `GET /api/v1/agent/skills`；若 503 则隐藏 FAB
- FAB 点击 toggle 抽屉；首次打开若无 session → `POST /sessions`
- 发送消息：`fetch` + `ReadableStream` 解析 SSE（或 `EventSource` 不适用 POST，用手动解析）
- 处理 event：
  - `token` → 追加到当前 assistant 气泡
  - `artifact` → 调用 `openDefinitionModalForAi(JSON.parse(data).content)` + toast
  - `done` / `error` → 恢复发送按钮
- CSRF：POST 头带 `X-XSRF-TOKEN`（复用 `csrf.js` 的 `getCsrfToken()`）

SSE 解析示例：

```javascript
async function sendAgentMessage(sessionId, content, onEvent) {
    const response = await fetch(`/api/v1/agent/sessions/${sessionId}/messages`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'text/event-stream',
            'X-XSRF-TOKEN': getCsrfToken()
        },
        body: JSON.stringify({ content })
    });
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        // 按 \n\n 拆分 event 块，解析 event: / data: 行
        // 调用 onEvent(name, data)
    }
}
```

- [x] **Step 4: style.css**

```css
.ai-fab {
    position: fixed;
    right: 24px;
    bottom: 24px;
    z-index: 2000;
    width: 56px;
    height: 56px;
    border-radius: 50%;
    /* 主色、阴影 */
}
.ai-drawer {
    position: fixed;
    top: 0;
    right: 0;
    width: 400px;
    max-width: 100vw;
    height: 100vh;
    z-index: 1999;
    /* 背景、边框、flex 列布局 */
}
.ai-drawer.hidden, .ai-fab.hidden { display: none; }
@media (max-width: 640px) {
    .ai-drawer { width: 100vw; }
}
```

- [x] **Step 5: 手工验证清单（无 LLM 自动化）**

1. `data-generator.ai.enabled=false` → FAB 不可见
2. `enabled=true` 且无 API Key → 创建会话 503
3. 配置有效 Key 后：多轮对话、连接列表、artifact 开模态

---

## Task 10: 全量测试与文档

**Files:**
- Modify: `README.md`（简短增加 AI 配置说明，可选）
- Modify: `AGENTS.md`（模块结构增加 dg-ai，可选一行）

- [x] **Step 1: 全量测试**

Run: `mvn clean test -q`
Expected: BUILD SUCCESS

- [x] **Step 2: 打包验证**

Run: `mvn clean package -pl dg-web -am -DskipTests -q`
Expected: BUILD SUCCESS

- [x] **Step 3: README 增加 AI 配置段（可选）**

说明：

- `data-generator.ai.enabled=true`
- 配置 `providers` 与环境变量 `DEEPSEEK_API_KEY`
- 悬浮球入口与 Skill 同步方式（`.cursor/skills` → `dg-ai/resources/skills`）

---

## 实现顺序建议

```
Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10
```

Task 6 可与 Task 7 并行（Mock Port 先测 dg-ai），合并前需 Task 7 完成联调。

---

## 风险与注意事项

1. **LangChain4j 版本**：使用 `langchain4j` 1.0.0 **编程式 API**，勿引入 `langchain4j-spring-boot-starter`（其新版本依赖 Spring Boot 3.5+，与项目 3.3.5 冲突）。
2. **SSE + CSRF**：确保 POST `/messages` 携带 CSRF token；Spring Security 默认对 authenticated 用户启用 CSRF。
3. **JobSeedValidator**：`loadJobFromContent` 会触发 seed 校验，可能需 `ConfigPathResolver`；测试用 `classpathOnly` loader。
4. **Skill 同步**：修改 `.cursor/skills/generate-job/` 后须同步到 `dg-ai/src/main/resources/skills/`。
5. **P2 预留**：不在 P1 实现 `RemoteAgentClient`、`ai-standalone` 完整链路；配置项可占位。

---

## 验收清单（P1）

- [x] 登录后、`ai.enabled=true` 且 provider 配置正确时可见悬浮球
- [x] 选 `generate-job`，多轮对话可经 Tool 列出连接名（无 url/密码）
- [x] 完整 YAML 校验通过后才 SSE `artifact`
- [x] 收到 `artifact` 自动打开「新建任务」并填入 YAML
- [x] 用户保存后 Job 可正常运行
- [x] `mvn clean test` 通过
