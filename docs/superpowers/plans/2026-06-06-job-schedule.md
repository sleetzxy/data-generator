# Job 定时调度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Job 定义增加 Cron 定时调度；内置 Job schedule 存 YAML、自定义 Job schedule 存 SQLite；定时与手动触发共用 FIFO 排队；启用调度的 Job 仍可手动运行。

**Architecture:** `dg-core` 解析 YAML `schedule` 块；`dg-web` 新增 `JobScheduleManager`（Spring `TaskScheduler` + `CronTrigger`）、`JobScheduleExecutor`（按 `config_path` 排队）、`JobScheduleService`（合并 YAML/DB 配置）；`JobService` 拆分为 `submit` / `createQueuedJob` / `executeAccepted`，完成时回调 dequeue；Web UI 展示调度列与自定义 Job 调度设置弹窗。

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Scheduling, Spring JDBC, sqlite-jdbc, SnakeYAML, JUnit 5, Mockito, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-06-job-schedule-design.md`

---

## File Structure

```
dg-core/
├── src/main/java/com/datagenerator/core/schema/
│   ├── ScheduleDefinition.java              # 新建：enabled + cron
│   └── JobDefinition.java                   # 修改：新增 schedule 字段
├── src/test/java/com/datagenerator/core/schema/
│   ├── YamlConfigLoaderTest.java            # 修改：schedule 解析测试
│   └── fixtures/jobs/
│       └── scheduled_job.yaml               # 新建：含 schedule 块的 fixture

dg-web/
├── src/main/java/com/datagenerator/web/
│   ├── config/
│   │   └── SchedulingConfig.java            # 新建：@EnableScheduling + TaskScheduler
│   ├── dto/
│   │   ├── JobScheduleRequest.java          # 新建
│   │   ├── JobScheduleResponse.java         # 新建
│   │   ├── JobDefinitionResponse.java       # 修改：schedule 字段
│   │   ├── JobResponse.java                 # 修改：triggerSource（可选）
│   │   └── TriggerSource.java               # 新建：enum MANUAL/SCHEDULED
│   ├── exception/
│   │   ├── ReadOnlyScheduleException.java   # 新建
│   │   └── GlobalExceptionHandler.java      # 修改：403 handler
│   ├── storage/
│   │   ├── SqliteSchemaInitializer.java     # 修改：job_schedules + trigger_source
│   │   ├── JobRepository.java               # 修改：trigger_source + findRunningByJobConfig
│   │   └── JobScheduleRepository.java       # 新建
│   ├── service/
│   │   ├── JobScheduleService.java          # 新建
│   │   ├── JobScheduleManager.java          # 新建
│   │   ├── JobScheduleExecutor.java         # 新建
│   │   ├── JobDefinitionService.java        # 修改：schedule 合并、校验、删除清理
│   │   └── JobService.java                  # 修改：拆分 submit/queue/execute + 回调
│   └── controller/
│       ├── JobDefinitionController.java     # 修改：GET/PUT /{name}/schedule
│       └── JobController.java               # 修改：submit 走 Executor（间接经 JobService）
├── src/main/resources/static/
│   ├── index.html                           # 修改：调度列 + 调度弹窗
│   └── app.js                               # 修改：schedule UI 逻辑
└── src/test/java/com/datagenerator/web/
    ├── storage/
    │   ├── SqliteSchemaInitializerTest.java   # 新建
    │   ├── JobRepositoryTest.java           # 修改/新建
    │   └── JobScheduleRepositoryTest.java   # 新建
    └── service/
        ├── JobScheduleServiceTest.java        # 新建
        ├── JobScheduleExecutorTest.java       # 新建
        ├── JobScheduleManagerTest.java        # 新建
        ├── JobDefinitionServiceTest.java    # 修改
        ├── JobServiceTest.java              # 新建或修改
        └── controller/
            ├── JobDefinitionControllerTest.java  # 修改
            └── JobControllerTest.java            # 修改
```

---

## Task 1: dg-core — ScheduleDefinition 与 YAML 解析

**Files:**
- Create: `dg-core/src/main/java/com/datagenerator/core/schema/ScheduleDefinition.java`
- Modify: `dg-core/src/main/java/com/datagenerator/core/schema/JobDefinition.java`
- Modify: `dg-core/src/main/java/com/datagenerator/core/schema/YamlConfigLoader.java`
- Create: `dg-core/src/test/resources/fixtures/jobs/scheduled_job.yaml`
- Modify: `dg-core/src/test/java/com/datagenerator/core/schema/YamlConfigLoaderTest.java`

- [ ] **Step 1: 编写失败测试**

在 `YamlConfigLoaderTest.java` 添加：

```java
@Test
void loadJob_withSchedule_parsesEnabledAndCron() {
    JobDefinition job = loader.loadJob("fixtures/jobs/scheduled_job.yaml");
    assertThat(job.getSchedule()).isPresent();
    ScheduleDefinition schedule = job.getSchedule().orElseThrow();
    assertThat(schedule.isEnabled()).isTrue();
    assertThat(schedule.getCron()).isEqualTo("0 0 2 * * ?");
}
```

Fixture `scheduled_job.yaml`：

```yaml
id: scheduled_job
name: 定时任务示例
schedule:
  enabled: true
  cron: "0 0 2 * * ?"
tables:
  - name: t1
    count: 1
    schema: fixtures/schemas/customer.yaml
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl dg-core test -Dtest=YamlConfigLoaderTest#loadJob_withSchedule_parsesEnabledAndCron -q`
Expected: FAIL（`getSchedule` 不存在）

- [ ] **Step 3: 实现 ScheduleDefinition + JobDefinition + loadJob 解析**

`ScheduleDefinition.java`：

```java
package com.datagenerator.core.schema;

public class ScheduleDefinition {
    private boolean enabled;
    private String cron;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
}
```

`JobDefinition` 增加：

```java
private ScheduleDefinition schedule;

public java.util.Optional<ScheduleDefinition> getSchedule() {
    return java.util.Optional.ofNullable(schedule);
}
public void setSchedule(ScheduleDefinition schedule) { this.schedule = schedule; }
```

在 `YamlConfigLoader.loadJob` 末尾解析：

```java
Object scheduleValue = root.get("schedule");
if (scheduleValue instanceof Map<?, ?> scheduleMap) {
    ScheduleDefinition schedule = new ScheduleDefinition();
    schedule.setEnabled(Boolean.TRUE.equals(scheduleMap.get("enabled")));
    schedule.setCron(YamlMappingUtils.asString(scheduleMap.get("cron")));
    job.setSchedule(schedule);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl dg-core test -Dtest=YamlConfigLoaderTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add dg-core/src/main/java/com/datagenerator/core/schema/ScheduleDefinition.java \
        dg-core/src/main/java/com/datagenerator/core/schema/JobDefinition.java \
        dg-core/src/main/java/com/datagenerator/core/schema/YamlConfigLoader.java \
        dg-core/src/test/resources/fixtures/jobs/scheduled_job.yaml \
        dg-core/src/test/java/com/datagenerator/core/schema/YamlConfigLoaderTest.java
git commit -m "feat(core): 解析 Job YAML schedule 块"
```

---

## Task 2: SQLite Schema 扩展

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/storage/SqliteSchemaInitializer.java`
- Create: `dg-web/src/test/java/com/datagenerator/web/storage/SqliteSchemaInitializerTest.java`

- [ ] **Step 1: 编写失败测试**

```java
@Test
void initialize_addsTriggerSourceColumn_idempotent() {
    JdbcTemplate jdbc = SqliteTestSupport.createJdbcTemplate();
    SqliteSchemaInitializer.initialize(jdbc);
    SqliteSchemaInitializer.initialize(jdbc); // 第二次不应抛异常
    Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='job_schedules'",
            Integer.class);
    assertThat(count).isEqualTo(1);
    assertThat(columnExists(jdbc, "jobs", "trigger_source")).isTrue();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl dg-web test -Dtest=SqliteSchemaInitializerTest -q`
Expected: FAIL

- [ ] **Step 3: 实现 ensureColumn + 新表**

在 `SqliteSchemaInitializer` 添加：

```java
jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS job_schedules (
            config_path TEXT PRIMARY KEY,
            enabled INTEGER NOT NULL DEFAULT 0,
            cron TEXT,
            updated_at TEXT NOT NULL
        )
        """);
ensureColumn(jdbcTemplate, "jobs", "trigger_source", "TEXT");
```

`ensureColumn` 用 `PRAGMA table_info(jobs)` 检查列是否存在。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl dg-web test -Dtest=SqliteSchemaInitializerTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(web): SQLite 新增 job_schedules 表与 trigger_source 列"
```

---

## Task 3: JobRepository 扩展

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/storage/JobRepository.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/dto/JobResponse.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/dto/TriggerSource.java`
- Modify: `dg-web/src/test/java/com/datagenerator/web/storage/JobRepositoryTest.java`（若无则新建）

- [ ] **Step 1: 编写失败测试**

```java
@Test
void findRunningByJobConfig_returnsOnlyRunning() {
    insertJob("j1", "jobs/a.yaml", JobStatus.RUNNING);
    insertJob("j2", "jobs/a.yaml", JobStatus.COMPLETED);
    assertThat(repository.findRunningByJobConfig("jobs/a.yaml"))
            .extracting(JobResponse::getJobId)
            .containsExactly("j1");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl dg-web test -Dtest=JobRepositoryTest#findRunningByJobConfig_returnsOnlyRunning -q`

- [ ] **Step 3: 实现**

- `TriggerSource` enum：`MANUAL`, `SCHEDULED`
- `JobResponse` 增加 `triggerSource` 字段
- `insert`/`update`/`mapRow` SQL 包含 `trigger_source`
- 新增 `findRunningByJobConfig(String configPath)`

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl dg-web test -Dtest=JobRepositoryTest -q`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(web): JobRepository 支持 trigger_source 与按 config 查 RUNNING"
```

---

## Task 4: JobScheduleRepository

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/storage/JobScheduleRepository.java`
- Create: `dg-web/src/test/java/com/datagenerator/web/storage/JobScheduleRepositoryTest.java`

- [ ] **Step 1: 编写失败测试**

```java
@Test
void upsertAndFind_roundTrip() {
    repo.upsert("jobs/my.yaml", true, "0 0 2 * * ?", Instant.now().toString());
    Optional<JobScheduleRecord> found = repo.findByConfigPath("jobs/my.yaml");
    assertThat(found).isPresent();
    assertThat(found.get().enabled()).isTrue();
    assertThat(found.get().cron()).isEqualTo("0 0 2 * * ?");
}

@Test
void deleteByConfigPath_removesRow() {
    repo.upsert("jobs/x.yaml", false, null, Instant.now().toString());
    repo.deleteByConfigPath("jobs/x.yaml");
    assertThat(repo.findByConfigPath("jobs/x.yaml")).isEmpty();
}
```

- [ ] **Step 2–4: TDD 实现 record + upsert/find/delete/deleteOrphans**

Run: `mvn -pl dg-web test -Dtest=JobScheduleRepositoryTest -q`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(web): 新增 JobScheduleRepository"
```

---

## Task 5: DTO 与 ReadOnlyScheduleException

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/dto/JobScheduleRequest.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/dto/JobScheduleResponse.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/exception/ReadOnlyScheduleException.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: 实现 DTO**

`JobScheduleRequest`：`enabled`, `cron`

`JobScheduleResponse`：`enabled`, `cron`, `editable`, `nextRunAt`

- [ ] **Step 2: 实现异常处理**

```java
@ExceptionHandler(ReadOnlyScheduleException.class)
public ResponseEntity<ErrorResponse> handleReadOnlySchedule(ReadOnlyScheduleException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse(HttpStatus.FORBIDDEN.value(), ex.getMessage()));
}
```

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(web): 新增调度 DTO 与 ReadOnlyScheduleException"
```

---

## Task 6: JobScheduleService

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/service/JobScheduleService.java`
- Create: `dg-web/src/test/java/com/datagenerator/web/service/JobScheduleServiceTest.java`

- [ ] **Step 1: 编写失败测试**

覆盖：
- `resolveSchedule_builtinJob_readsYaml`
- `resolveSchedule_customJob_readsSqlite`
- `validateSchedule_enabledTrueRequiresValidCron`
- `validateSchedule_enabledFalse_allowsEmptyCron`
- `computeNextRunAt_returnsIso8601WhenEnabled`

- [ ] **Step 2–4: TDD 实现**

核心逻辑：
- 内置（`isBuiltin(configPath)`）：从 `YamlConfigLoader.loadJob` 读 `schedule`，`editable=false`
- 自定义：从 `JobScheduleRepository`，`editable=true`
- Cron 校验：`CronExpression.isValid(cron)`
- `nextRunAt`：`CronExpression.parse(cron).next(LocalDateTime.now())` 转 ISO-8601

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(web): 新增 JobScheduleService 合并 YAML/SQLite 调度配置"
```

---

## Task 7: JobService 拆分与 triggerSource

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/service/JobService.java`
- Create/Modify: `dg-web/src/test/java/com/datagenerator/web/service/JobServiceTest.java`

- [ ] **Step 1: 编写失败测试**

```java
@Test
void submitScheduled_forcesAsync() { /* mock，estimatedRows 小于 threshold 仍 async */ }

@Test
void createQueuedJob_insertsPendingWithoutExecuting() { /* 仅 INSERT，不调用 orchestrator */ }
```

- [ ] **Step 2–4: 重构 JobService**

新增/调整方法：

```java
public JobSubmitResult submit(JobSubmitRequest request) {
    return scheduleExecutor.enqueue(request.getJobConfig(), TriggerSource.MANUAL, request);
}

public JobResponse createQueuedJob(String configPath, TriggerSource triggerSource) {
    // generateJobId, INSERT PENDING, trigger_source, log "已加入队列"
}

public void executeAccepted(String jobId) {
    // 从 repository 加载，load job definition，走 executeAndStore / async
}

private JobSubmitResult doSubmit(JobSubmitRequest request, TriggerSource triggerSource) {
    // 原 submit 主体；SCHEDULED 时 forceAsync=true
}
```

`executeAndStore` / `executeAccepted` 的 `finally`：

```java
scheduleExecutor.onJobTerminal(jobConfig);
```

`cancel` 成功后同样调用 `onJobTerminal`。

注入：`@Lazy JobScheduleExecutor scheduleExecutor`

- [ ] **Step 5: 运行相关测试**

Run: `mvn -pl dg-web test -Dtest=JobServiceTest,JobServiceAsyncTest -q`

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor(web): JobService 拆分排队提交与 executeAccepted"
```

---

## Task 8: JobScheduleExecutor 排队

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/service/JobScheduleExecutor.java`
- Create: `dg-web/src/test/java/com/datagenerator/web/service/JobScheduleExecutorTest.java`

- [ ] **Step 1: 编写失败测试**

```java
@Test
void enqueue_whenRunning_queuesSecondJob() {
    // mock JobRepository.findRunningByJobConfig 返回 RUNNING
    // 第一次 enqueue → doSubmit
    // 第二次 enqueue → createQueuedJob + 202
}

@Test
void onJobTerminal_dequeuesNext() {
    // 预置队列有一项 → onJobTerminal → verify executeAccepted
}

@Test
void cancelRunningJob_dequeuesNext() { /* 同上 */ }
```

- [ ] **Step 2–4: 实现**

- `ConcurrentHashMap<String, JobScheduleQueue>`，每队列 `synchronized`
- `QueuedItem(String jobId, TriggerSource trigger, JobSubmitRequest request)`
- `enqueue` 判定：`runningExists || !queue.isEmpty()` → 排队
- `clearQueue(configPath)` 供 DELETE 定义时使用

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(web): 新增 JobScheduleExecutor FIFO 排队"
```

---

## Task 9: JobScheduleManager + SchedulingConfig

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/config/SchedulingConfig.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/service/JobScheduleManager.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/storage/JobStartupRecovery.java`（添加 `@Order(1)`）
- Create: `dg-web/src/test/java/com/datagenerator/web/service/JobScheduleManagerTest.java`

- [ ] **Step 1: SchedulingConfig**

```java
@Configuration
@EnableScheduling
public class SchedulingConfig {
    @Bean
    ThreadPoolTaskScheduler jobTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("job-schedule-");
        scheduler.initialize();
        return scheduler;
    }
}
```

- [ ] **Step 2: JobScheduleManager**

- `@Order(2)` `@EventListener(ApplicationReadyEvent.class) onReady()`
- 遍历 `JobDefinitionService.list()` 或 pathResolver 全部 jobs
- `JobScheduleService.resolveSchedule` → enabled + valid cron → `scheduler.schedule(() -> executor.enqueue(path, SCHEDULED, null), new CronTrigger(cron))`
- `reschedule(configPath)` / `cancel(configPath)` 管理 `ScheduledFuture`
- 启动时 `deleteOrphans`（DB 中有但 YAML 不存在的 config_path）

- [ ] **Step 3: 测试**

Mock `ThreadPoolTaskScheduler` 或使用 `@SpringBootTest` 验证 `@Order` 与 reschedule 调用。

Run: `mvn -pl dg-web test -Dtest=JobScheduleManagerTest -q`

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(web): 新增 JobScheduleManager Cron 注册与启动加载"
```

---

## Task 10: JobDefinitionService 与 Controller

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/service/JobDefinitionService.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/controller/JobDefinitionController.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/dto/JobDefinitionResponse.java`
- Modify: `dg-web/src/test/java/com/datagenerator/web/service/JobDefinitionServiceTest.java`
- Modify: `dg-web/src/test/java/com/datagenerator/web/controller/JobDefinitionControllerTest.java`

- [ ] **Step 1: validateContent 拒绝自定义 YAML 的 schedule 键**

```java
if (!isBuiltin(configPath) && root.containsKey("schedule")) {
    throw new IllegalArgumentException("Custom job YAML must not contain schedule block");
}
```

- [ ] **Step 2: list/get toResponse 附带 schedule**

委托 `JobScheduleService.resolveSchedule(configPath, builtin)`

- [ ] **Step 3: delete 清理 schedule**

调用 `scheduleManager.cancel` + `executor.clearQueue` + `scheduleRepository.delete`

- [ ] **Step 4: Controller 端点**

```java
@GetMapping("/{name}/schedule")
public JobScheduleResponse getSchedule(@PathVariable("name") String name) { ... }

@PutMapping("/{name}/schedule")
public JobScheduleResponse updateSchedule(
        @PathVariable("name") String name,
        @RequestBody JobScheduleRequest request) { ... }
```

内置 PUT → `ReadOnlyScheduleException`

- [ ] **Step 5: 测试**

```java
@Test
void updateSchedule_builtinJob_returns403() throws Exception { ... }

@Test
void create_customJobWithScheduleBlock_rejected() { ... }
```

Run: `mvn -pl dg-web test -Dtest=JobDefinitionControllerTest,JobDefinitionServiceTest -q`

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(web): Job 定义 schedule API 与 YAML 校验"
```

---

## Task 11: JobController 排队响应

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/controller/JobController.java`
- Modify: `dg-web/src/test/java/com/datagenerator/web/controller/JobControllerTest.java`

- [ ] **Step 1: 确认 submitJob 经 JobService.submit → Executor**

排队时返回 202 + jobId（PENDING）。

- [ ] **Step 2: 测试**

```java
@Test
void submitJob_whenQueued_returns202WithJobId() throws Exception { ... }
```

Run: `mvn -pl dg-web test -Dtest=JobControllerTest -q`

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(web): Job 提交支持排队 202 响应"
```

---

## Task 12: Web UI

**Files:**
- Modify: `dg-web/src/main/resources/static/index.html`
- Modify: `dg-web/src/main/resources/static/app.js`
- Modify: `dg-web/src/main/resources/static/style.css`（如需 badge 样式）

- [ ] **Step 1: index.html 表头增加「调度」「Cron」列**

调整 `colspan` 与 modal：新增 `#schedule-modal`（enabled 开关、cron 输入、nextRunAt 展示、保存按钮）。

- [ ] **Step 2: app.js**

- `loadDefinitions` 渲染 `item.schedule`：enabled badge + cron 文本
- 自定义 Job「更多」菜单增加「调度设置」→ `openScheduleModal(fileName)`
- `saveSchedule()` → `PUT /job-definitions/${fileName}/schedule`
- `runDefinition`：若 `result.status === 'PENDING'` 且响应头或 body 表明 queued，toast 排队文案

- [ ] **Step 3: 手动验证**

Run: `mvn -pl dg-web package -DskipTests && java -jar dg-web/target/dg-web-0.1.0-SNAPSHOT.jar`

验证：自定义 Job 设置 cron → 列表展示 → 手动运行 → 运行中再点运行 → 排队 toast

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(web): 控制台支持 Job 调度配置与展示"
```

---

## Task 13: 全量测试与 Spec 状态更新

**Files:**
- Modify: `docs/superpowers/specs/2026-06-06-job-schedule-design.md`（状态 → 已批准）

- [ ] **Step 1: 全量测试**

Run: `mvn clean test`
Expected: BUILD SUCCESS

- [ ] **Step 2: 修复失败测试**

逐个模块修复直至通过。

- [ ] **Step 3: 更新 spec 状态**

```markdown
**状态：** 已批准
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-06-06-job-schedule-design.md
git commit -m "test: Job 定时调度全量测试通过"
```

---

## 执行顺序依赖

```
Task 1 (core YAML)
  → Task 2 (schema) → Task 3 (JobRepository) → Task 4 (JobScheduleRepository)
  → Task 5 (DTO/exception)
  → Task 6 (JobScheduleService)
  → Task 7 (JobService) → Task 8 (Executor)   # 7 与 8 可交错，但需 @Lazy 破环
  → Task 9 (Manager)
  → Task 10 (Definition API) → Task 11 (JobController)
  → Task 12 (UI)
  → Task 13 (全量验证)
```

---

## 风险与注意事项

1. **环依赖：** `JobService` ↔ `JobScheduleExecutor` 必须 `@Lazy` 一侧。
2. **既有测试：** `JobServiceAsyncTest` 等可能因 `submit` 签名/行为变化需更新 Mock 或注入 Executor。
3. **SQLite 迁移：** 已有 `dg-jobs.db` 部署需验证 `ensureColumn` 幂等。
4. **Cron 格式：** 文档与 UI placeholder 使用 Spring 6 字段（秒 分 时 日 月 周），非 Unix 5 字段。
