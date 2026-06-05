# 任务运行日志 SQLite 持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将任务记录与运行日志从内存迁移至 SQLite，重启后历史可查，遗留 RUNNING/PENDING 任务自动标记 CANCELLED。

**Architecture:** 在 `dg-web` 新增 `storage` 包，Spring JDBC + sqlite-jdbc 操作 `jobs` / `job_logs` 两张表；`JobLogStore` 接口不变、内部委托 `JobLogRepository`；`JobService` 与 `AsyncJobExecutor` 改用 `JobRepository` 替代 `ConcurrentHashMap`；启动时 `JobStartupRecovery` 处理遗留任务。

**Tech Stack:** Java 21, Spring Boot 3.3, Spring JDBC, sqlite-jdbc, Jackson (details_json), JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-05-job-log-sqlite-design.md`

---

## File Structure

```
dg-web/
├── pom.xml                                          # 新增 jdbc + sqlite-jdbc 依赖
├── src/main/java/com/datagenerator/web/
│   ├── config/
│   │   └── DataGeneratorProperties.java           # 新增 StorageProperties 嵌套类
│   ├── storage/
│   │   ├── SqliteSchemaInitializer.java           # 建表 + WAL（主/测试共用）
│   │   ├── SqliteDataSourceConfig.java            # DataSource Bean
│   │   ├── JobRepository.java                     # jobs CRUD
│   │   ├── JobLogRepository.java                  # job_logs append/query/delete
│   │   └── JobStartupRecovery.java                # 启动恢复
│   └── service/
│       ├── JobLogStore.java                       # 改为委托 JobLogRepository
│       ├── JobService.java                        # 移除 ConcurrentHashMap
│       └── AsyncJobExecutor.java                  # 改为 @Component
├── src/main/resources/
│   └── application.yml                              # 可选：显式 storage.sqlite-path
└── src/test/java/com/datagenerator/web/
    ├── storage/
    │   ├── SqliteTestSupport.java                   # 测试用 :memory: DataSource 工厂
    │   ├── JobRepositoryTest.java
    │   ├── JobLogRepositoryTest.java
    │   └── JobStartupRecoveryTest.java
    └── service/
        ├── JobServiceAsyncTest.java                 # 改用 SQLite 支撑类
        ├── JobServiceWriterResolutionTest.java
        └── AsyncJobExecutorCancelTest.java
```

---

## Task 1: Maven 依赖

**Files:**
- Modify: `pom.xml`（父 POM，dependencyManagement）
- Modify: `dg-web/pom.xml`

- [ ] **Step 1: 父 POM 添加 sqlite-jdbc 版本管理**

在 `pom.xml` `<properties>` 添加：

```xml
<sqlite-jdbc.version>3.46.1.0</sqlite-jdbc.version>
```

在 `<dependencyManagement><dependencies>` 添加：

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>${sqlite-jdbc.version}</version>
</dependency>
```

- [ ] **Step 2: dg-web 添加运行时依赖**

在 `dg-web/pom.xml` `<dependencies>` 添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
</dependency>
```

- [ ] **Step 3: 验证编译**

Run: `mvn -pl dg-web compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add pom.xml dg-web/pom.xml
git commit -m "build: 添加 Spring JDBC 与 sqlite-jdbc 依赖"
```

---

## Task 2: 配置属性

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/config/DataGeneratorProperties.java`
- Modify: `dg-web/src/main/resources/application.yml`（可选注释）

- [ ] **Step 1: 在 DataGeneratorProperties 添加 StorageProperties 嵌套类**

```java
private StorageProperties storage = new StorageProperties();

public StorageProperties getStorage() {
    return storage;
}

public void setStorage(StorageProperties storage) {
    this.storage = storage;
}

public static class StorageProperties {
    private String sqlitePath = "./data/dg-jobs.db";

    public String getSqlitePath() {
        return sqlitePath;
    }

    public void setSqlitePath(String sqlitePath) {
        this.sqlitePath = sqlitePath;
    }
}
```

- [ ] **Step 2: application.yml 添加注释示例（可选）**

```yaml
data-generator:
  # storage:
  #   sqlite-path: ./data/dg-jobs.db
```

- [ ] **Step 3: 验证编译**

Run: `mvn -pl dg-web compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add dg-web/src/main/java/com/datagenerator/web/config/DataGeneratorProperties.java dg-web/src/main/resources/application.yml
git commit -m "feat(web): 添加 SQLite 存储路径配置项"
```

---

## Task 3: Schema 初始化与 DataSource

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/storage/SqliteSchemaInitializer.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/storage/SqliteDataSourceConfig.java`

- [ ] **Step 1: 创建 SqliteSchemaInitializer**

```java
package com.datagenerator.web.storage;

import org.springframework.jdbc.core.JdbcTemplate;

public final class SqliteSchemaInitializer {

    private SqliteSchemaInitializer() {
    }

    public static void initialize(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS jobs (
                    job_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    job_config TEXT,
                    submitted_at TEXT NOT NULL,
                    duration TEXT,
                    error_message TEXT,
                    total_tables INTEGER NOT NULL DEFAULT 0,
                    completed_tables INTEGER NOT NULL DEFAULT 0,
                    total_rows INTEGER NOT NULL DEFAULT 0,
                    written_rows INTEGER NOT NULL DEFAULT 0,
                    failed_rows INTEGER NOT NULL DEFAULT 0,
                    details_json TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_jobs_submitted_at
                ON jobs(submitted_at DESC)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS job_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id TEXT NOT NULL,
                    logged_at TEXT NOT NULL,
                    level TEXT NOT NULL,
                    message TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_job_logs_job_id
                ON job_logs(job_id, id)
                """);
    }
}
```

- [ ] **Step 2: 创建 SqliteDataSourceConfig**

```java
package com.datagenerator.web.storage;

import com.datagenerator.web.config.DataGeneratorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class SqliteDataSourceConfig {

    @Bean
    DataSource dataSource(DataGeneratorProperties properties) throws IOException {
        Path dbPath = Path.of(properties.getStorage().getSqlitePath()).toAbsolutePath().normalize();
        Files.createDirectories(dbPath.getParent());
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        SqliteSchemaInitializer.initialize(jdbcTemplate);
        return jdbcTemplate;
    }
}
```

- [ ] **Step 3: 验证 Spring 上下文启动**

Run: `mvn -pl dg-web test -Dtest=JobControllerTest -q`
Expected: PASS（现有 WebMvcTest 不加载 DataSource，应不受影响）

- [ ] **Step 4: Commit**

```bash
git add dg-web/src/main/java/com/datagenerator/web/storage/
git commit -m "feat(web): 添加 SQLite DataSource 与建表初始化"
```

---

## Task 4: JobRepository（TDD）

**Files:**
- Create: `dg-web/src/test/java/com/datagenerator/web/storage/SqliteTestSupport.java`
- Create: `dg-web/src/test/java/com/datagenerator/web/storage/JobRepositoryTest.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/storage/JobRepository.java`

- [ ] **Step 1: 创建测试支撑类 SqliteTestSupport**

```java
package com.datagenerator.web.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class SqliteTestSupport {

    private SqliteTestSupport() {
    }

    public static JdbcTemplate createInMemoryJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        SqliteSchemaInitializer.initialize(jdbcTemplate);
        return jdbcTemplate;
    }
}
```

- [ ] **Step 2: 写失败测试 JobRepositoryTest**

```java
package com.datagenerator.web.storage;

import com.datagenerator.web.dto.JobProgress;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.TableDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JobRepositoryTest {

    private JobRepository repository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        repository = new JobRepository(jdbcTemplate);
    }

    @Test
    void insert_and_findById_returnsJob() {
        JobResponse job = sampleJob("job-1", JobStatus.PENDING);
        repository.insert(job);

        Optional<JobResponse> found = repository.findById("job-1");
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(found.get().getJobConfig()).isEqualTo("jobs/test.yaml");
    }

    @Test
    void update_persistsStatusAndProgress() {
        repository.insert(sampleJob("job-2", JobStatus.PENDING));
        JobResponse running = sampleJob("job-2", JobStatus.RUNNING);
        running.setProgress(new JobProgress(2, 1, 100, 50, 0));
        running.setDuration("1.2s");

        repository.update(running);

        JobResponse found = repository.findById("job-2").orElseThrow();
        assertThat(found.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(found.getProgress().getWrittenRows()).isEqualTo(50);
        assertThat(found.getDuration()).isEqualTo("1.2s");
    }

    @Test
    void listAll_ordersBySubmittedAtDesc() throws InterruptedException {
        JobResponse older = sampleJob("job-old", JobStatus.COMPLETED);
        older.setSubmittedAt("2026-01-01T00:00:00Z");
        JobResponse newer = sampleJob("job-new", JobStatus.COMPLETED);
        newer.setSubmittedAt("2026-06-01T00:00:00Z");
        repository.insert(older);
        repository.insert(newer);

        List<JobResponse> all = repository.listAll();
        assertThat(all).extracting(JobResponse::getJobId).containsExactly("job-new", "job-old");
    }

    @Test
    void delete_removesJob() {
        repository.insert(sampleJob("job-del", JobStatus.COMPLETED));
        repository.delete("job-del");
        assertThat(repository.findById("job-del")).isEmpty();
    }

    @Test
    void findByStatusIn_returnsMatchingJobs() {
        repository.insert(sampleJob("job-run", JobStatus.RUNNING));
        repository.insert(sampleJob("job-done", JobStatus.COMPLETED));

        assertThat(repository.findByStatusIn(List.of(JobStatus.RUNNING, JobStatus.PENDING)))
                .extracting(JobResponse::getJobId)
                .containsExactly("job-run");
    }

    private static JobResponse sampleJob(String jobId, JobStatus status) {
        JobResponse response = new JobResponse(
                jobId,
                status,
                new JobProgress(1, 0, 10, 0, 0),
                List.of(new TableDetail("t1", 0, "pending")),
                null,
                "jobs/test.yaml",
                "2026-06-05T00:00:00Z",
                null,
                null);
        return response;
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -pl dg-web test -Dtest=JobRepositoryTest -q`
Expected: FAIL（JobRepository 不存在）

- [ ] **Step 4: 实现 JobRepository**

要点：
- 添加 `@Repository` 注解，注册为 Spring Bean
- 使用 `JdbcTemplate` + `ObjectMapper`（构造函数注入或内部 `new ObjectMapper()`）
- `insert(JobResponse)` — INSERT 全字段
- `update(JobResponse)` — UPDATE 全字段（不含 job_id）
- `findById` / `listAll` / `delete` / `findByStatusIn`
- `details_json`：`ObjectMapper.writeValueAsString(response.getDetails())`，读取时反序列化为 `List<TableDetail>`
- `logged_at` 列名只在 job_logs 表；jobs 表用 `submitted_at`

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl dg-web test -Dtest=JobRepositoryTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add dg-web/src/main/java/com/datagenerator/web/storage/JobRepository.java dg-web/src/test/java/com/datagenerator/web/storage/
git commit -m "feat(web): 实现 JobRepository SQLite 持久化"
```

---

## Task 5: JobLogRepository（TDD）

**Files:**
- Create: `dg-web/src/test/java/com/datagenerator/web/storage/JobLogRepositoryTest.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/storage/JobLogRepository.java`

- [ ] **Step 1: 写失败测试**

```java
package com.datagenerator.web.storage;

import com.datagenerator.web.dto.JobLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobLogRepositoryTest {

    private JobLogRepository repository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        repository = new JobLogRepository(jdbcTemplate);
    }

    @Test
    void append_preservesInsertionOrder() {
        repository.append("job-1", "INFO", "first");
        repository.append("job-1", "WARN", "second");

        List<JobLogEntry> logs = repository.findByJobId("job-1");
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getMessage()).isEqualTo("first");
        assertThat(logs.get(1).getLevel()).isEqualTo("WARN");
    }

    @Test
    void deleteByJobId_removesAllLogs() {
        repository.append("job-2", "INFO", "msg");
        repository.deleteByJobId("job-2");
        assertThat(repository.findByJobId("job-2")).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl dg-web test -Dtest=JobLogRepositoryTest -q`
Expected: FAIL

- [ ] **Step 3: 实现 JobLogRepository**

要点：
- 添加 `@Repository` 注解，注册为 Spring Bean
- `append(jobId, level, message)` — INSERT，`logged_at = Instant.now().toString()`
- `findByJobId` — `SELECT logged_at, level, message FROM job_logs WHERE job_id=? ORDER BY id ASC`，映射到 `JobLogEntry.timestamp`
- `deleteByJobId`
- append 失败时抛异常，由 `JobLogStore` 捕获

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl dg-web test -Dtest=JobLogRepositoryTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add dg-web/src/main/java/com/datagenerator/web/storage/JobLogRepository.java dg-web/src/test/java/com/datagenerator/web/storage/JobLogRepositoryTest.java
git commit -m "feat(web): 实现 JobLogRepository 日志持久化"
```

---

## Task 6: 重构 JobLogStore

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/service/JobLogStore.java`

- [ ] **Step 1: 改为委托 JobLogRepository**

```java
@Component
public class JobLogStore {

    private static final Logger log = LoggerFactory.getLogger(JobLogStore.class);
    private final JobLogRepository jobLogRepository;

    public JobLogStore(JobLogRepository jobLogRepository) {
        this.jobLogRepository = jobLogRepository;
    }

    public void append(String jobId, String level, String message) {
        try {
            jobLogRepository.append(jobId, level, message);
        } catch (Exception exception) {
            log.error("Failed to append log for job {}: {}", jobId, message, exception);
        }
    }

    // info / warn / error 不变

    public List<JobLogEntry> getLogs(String jobId) {
        return jobLogRepository.findByJobId(jobId);
    }

    public void remove(String jobId) {
        jobLogRepository.deleteByJobId(jobId);
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `mvn -pl dg-web compile -q`
Expected: 编译通过（JobService/AsyncJobExecutor 测试暂不可编译，Task 8 一并修复）

**注意：** Task 6 完成后至 Task 8 完成前，`JobService` 相关测试无法编译，属预期中间态。

- [ ] **Step 3: Commit**

```bash
git add dg-web/src/main/java/com/datagenerator/web/service/JobLogStore.java
git commit -m "refactor(web): JobLogStore 委托 JobLogRepository"
```

---

## Task 7: JobStartupRecovery

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/storage/JobStartupRecovery.java`
- Create: `dg-web/src/test/java/com/datagenerator/web/storage/JobStartupRecoveryTest.java`

- [ ] **Step 1: 写失败测试**

```java
@BeforeEach
void setUp() {
    JdbcTemplate jdbc = SqliteTestSupport.createInMemoryJdbcTemplate();
    jobRepository = new JobRepository(jdbc);
    jobLogStore = new JobLogStore(new JobLogRepository(jdbc));
    recovery = new JobStartupRecovery(jobRepository, jobLogStore);
}

@Test
void recover_marksRunningJobCancelledAndWritesLog() {
    JobResponse running = sampleJob("job-run", JobStatus.RUNNING);
    jobRepository.insert(running);

    recovery.recover();

    assertThat(jobRepository.findById("job-run").orElseThrow().getStatus())
            .isEqualTo(JobStatus.CANCELLED);
    assertThat(jobLogStore.getLogs("job-run"))
            .anyMatch(entry -> entry.getMessage().contains("服务重启"));
}

@Test
void recover_marksPendingJobCancelled() {
    jobRepository.insert(sampleJob("job-pending", JobStatus.PENDING));

    recovery.recover();

    assertThat(jobRepository.findById("job-pending").orElseThrow().getStatus())
            .isEqualTo(JobStatus.CANCELLED);
}
```

- [ ] **Step 2: 实现 JobStartupRecovery**

```java
@Component
public class JobStartupRecovery {

    private final JobRepository jobRepository;
    private final JobLogStore jobLogStore;

    public JobStartupRecovery(JobRepository jobRepository, JobLogStore jobLogStore) {
        this.jobRepository = jobRepository;
        this.jobLogStore = jobLogStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        recover();
    }

    void recover() {
        List<JobStatus> staleStatuses = List.of(JobStatus.PENDING, JobStatus.RUNNING);
        for (JobResponse job : jobRepository.findByStatusIn(staleStatuses)) {
            job.setStatus(JobStatus.CANCELLED);
            jobRepository.update(job);
            jobLogStore.warn(job.getJobId(), "服务重启，任务已取消");
        }
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `mvn -pl dg-web test -Dtest=JobStartupRecoveryTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add dg-web/src/main/java/com/datagenerator/web/storage/JobStartupRecovery.java dg-web/src/test/java/com/datagenerator/web/storage/JobStartupRecoveryTest.java
git commit -m "feat(web): 启动时将遗留 RUNNING/PENDING 任务标记为 CANCELLED"
```

---

## Task 8: 重构 AsyncJobExecutor 与 JobService（同步提交，保证编译通过）

**说明：** 两个类互相依赖，必须同任务内一起改，避免中间态编译失败。

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/service/AsyncJobExecutor.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/service/JobService.java`
- Modify: `dg-web/src/test/java/com/datagenerator/web/service/AsyncJobExecutorCancelTest.java`
- Modify: `dg-web/src/test/java/com/datagenerator/web/service/JobServiceAsyncTest.java`
- Modify: `dg-web/src/test/java/com/datagenerator/web/service/JobServiceWriterResolutionTest.java`
- Create: `dg-web/src/test/java/com/datagenerator/web/service/JobServiceTestSupport.java`（可选，统一构造辅助）

- [ ] **Step 1: 创建 JobServiceTestSupport**

```java
public final class JobServiceTestSupport {

    public record JobServiceContext(
            JobRepository jobRepository,
            JobLogStore jobLogStore,
            AsyncJobExecutor asyncJobExecutor) {}

    public static JobServiceContext createContext(JobRuntimeSettings runtimeSettings) {
        JdbcTemplate jdbc = SqliteTestSupport.createInMemoryJdbcTemplate();
        JobRepository jobRepository = new JobRepository(jdbc);
        JobLogStore jobLogStore = new JobLogStore(new JobLogRepository(jdbc));
        AsyncJobExecutor asyncJobExecutor = new AsyncJobExecutor(
                runtimeSettings, jobRepository, jobLogStore);
        return new JobServiceContext(jobRepository, jobLogStore, asyncJobExecutor);
    }
}
```

- [ ] **Step 2: 重构 AsyncJobExecutor 为 @Component**

要点：
- 构造函数：`(JobRuntimeSettings settings, JobRepository jobRepository, JobLogStore jobLogStore)`
- 移除 `ConcurrentHashMap<String, JobResponse> jobs` 参数
- 所有 `jobs.get/put` 改为 `jobRepository.findById/update`
- 异步 catch：**仅当 status 仍为 RUNNING/PENDING 时** UPDATE FAILED 并写日志；若 `executeAndStore` 已写 FAILED，跳过重复 UPDATE 和 error 日志
- 保留 `futures` / `cancelled` 内存结构

- [ ] **Step 3: 重构 JobService**

- 移除 `ConcurrentHashMap` 和 `new AsyncJobExecutor(...)`
- 注入 `JobRepository`、`AsyncJobExecutor`
- 替换所有 jobs 操作为 jobRepository（见下表）
- **错误处理（规格 §7）：**
  - `submit` 时 `jobRepository.insert` 失败 → 抛异常，由全局异常处理返回 500
  - `executeAndStore` 中 `jobRepository.update` 失败 → catch 后标记 FAILED 并写入 error_message

| 原代码 | 新代码 |
|--------|--------|
| `jobs.put(jobId, placeholder)` | `jobRepository.insert(placeholder)` |
| `jobs.get(jobId)` | `jobRepository.findById(jobId).orElse(null)` |
| `jobs.put(jobId, response)` | `jobRepository.update(response)` |
| `jobs.containsKey(jobId)` | `jobRepository.findById(jobId).isPresent()` |
| `jobs.remove(jobId)` | `jobRepository.delete(jobId)` + `jobLogStore.remove(jobId)` |
| `jobs.values().stream()` | `jobRepository.listAll().stream()` |

- [ ] **Step 4: 更新全部 service 测试**

`JobServiceAsyncTest`、`JobServiceWriterResolutionTest`、`AsyncJobExecutorCancelTest` 均改用 `JobServiceTestSupport`；轮询状态改为 `jobRepository.findById(...).getStatus()`。

- [ ] **Step 5: 运行相关测试**

Run: `mvn -pl dg-web test -Dtest=AsyncJobExecutorCancelTest,JobServiceAsyncTest,JobServiceWriterResolutionTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add dg-web/src/main/java/com/datagenerator/web/service/ dg-web/src/test/java/com/datagenerator/web/service/
git commit -m "refactor(web): JobService 与 AsyncJobExecutor 迁移至 SQLite"
```

---

## Task 9: 全量验证

**Files:**
- Verify: 所有 dg-web 测试
- Optional: `dg-web/src/test/resources/application-test.yml` — 测试 profile 使用 `jdbc:sqlite::memory:` 避免污染 `./data/dg-jobs.db`

- [ ] **Step 1: 运行 dg-web 全量测试**

Run: `mvn -pl dg-web test -q`
Expected: BUILD SUCCESS（含 `EndToEndTest` Spring 上下文启动）

- [ ] **Step 2: 运行全项目测试**

Run: `mvn clean test -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 手动冒烟（可选）**

```bash
mvn -pl dg-web package -DskipTests -q
java -jar dg-web/target/dg-web-0.1.0-SNAPSHOT.jar
```

提交一个 job → 检查 `./data/dg-jobs.db` 生成 → 重启 → 确认历史任务仍可查询 → 确认遗留 RUNNING 变 CANCELLED。

- [ ] **Step 4: Commit（若有遗漏修复）**

```bash
git commit -m "test(web): 修复 SQLite 持久化相关测试"
```

---

## 验收标准

- [ ] 任务提交、执行、完成/失败/取消均写入 SQLite
- [ ] `GET /api/v1/jobs` 与 `GET /api/v1/jobs/{id}/logs` 重启后仍可用
- [ ] 重启后 RUNNING/PENDING → CANCELLED + WARN 日志
- [ ] 日志无 500 条上限
- [ ] `DELETE /api/v1/jobs/{id}/record` 级联删除日志
- [ ] `mvn clean test` 全绿
