# 移除内置任务与任务类型 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除内置/自定义任务区分，任务元数据（id、文件名、显示名、调度、时间戳）迁入 SQLite `tasks` 主表，YAML 只存生成配置；删除内置任务资源、`task_schedules` 表与全部 builtin 分支逻辑。

**Architecture:** 任务 = SQLite `tasks` 表行（元数据）+ `writable-config-dir/task-configs/{file_name}.yaml`（生成配置）。所有 Web CRUD 以表为准、文件为实；调度读写 `tasks` 表字段；运行前从表注入 id/name 到内存 TaskConfig（dg-core 模型不变）。不迁移旧数据（用户决策）。

**Tech Stack:** Java 21、Spring Boot 3.3、JdbcTemplate + SQLite、Maven 多模块、原生 JS 前端、JUnit 5 + Mockito + AssertJ。

**规格文档:** `docs/superpowers/specs/2026-09-02-remove-builtin-tasks-design.md`（已批准）

**重要约定:**
- 按项目 CLAUDE.md：Git commit 仅经用户授权后执行。本计划每个任务的 commit 步骤为占位，执行时须先征得用户同意。
- Java 重构中编译一致性优先于"每步可编译"：每个任务以"修改测试 → 运行确认失败 → 修改实现 → 运行确认通过"为单位，任务内部整体提交。
- 每个任务完成后必须运行对应模块测试并确认输出，再进入下一任务。

---

## 文件结构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| `dg-web/.../storage/TaskRepository.java` | 新建 | tasks 表仓储（元数据 CRUD + 调度字段） |
| `dg-web/.../storage/TaskScheduleRepository.java` | 删除 | 旧调度表仓储 |
| `dg-web/.../storage/SqliteSchemaInitializer.java` | 修改 | 建 tasks 表、DROP task_schedules |
| `dg-web/.../service/TaskConfigPaths.java` | 新建 | configPath ↔ fileName 转换工具 |
| `dg-web/.../service/TaskConfigService.java` | 重写 | 表驱动任务 CRUD |
| `dg-web/.../service/TaskScheduleService.java` | 重写 | 调度读写 tasks 表 |
| `dg-web/.../service/TaskScheduleManager.java` | 修改 | 启动重排查表、去 builtin 参数 |
| `dg-web/.../service/TaskRunService.java` | 修改 | 查表校验 + 运行时注入 id/name |
| `dg-web/.../controller/TaskConfigController.java` | 修改 | 适配 DTO |
| `dg-web/.../dto/TaskConfigRequest.java` | 修改 | name → fileName |
| `dg-web/.../dto/TaskConfigResponse.java` | 修改 | 去 builtin/readOnly |
| `dg-web/.../dto/TaskScheduleResponse.java` | 修改 | 去 editable |
| `dg-web/.../dto/TaskConfigListResponse.java` | 修改 | 去 skipped |
| `dg-web/.../dto/TaskConfigSkipInfo.java` | 删除 | 不再有扫描跳过 |
| `dg-web/.../exception/ReadOnlyScheduleException.java` | 删除 | 只读概念移除 |
| `dg-web/.../exception/TaskConfigNotFoundException.java` | 新建 | 任务不存在/文件缺失 → 404 |
| `dg-web/.../exception/GlobalExceptionHandler.java` | 修改 | 删 ReadOnlyScheduleException 映射、加 TaskConfigNotFoundException 映射 |
| `dg-core/.../model/YamlConfigLoader.java` | 修改 | 放宽 name/id 必填 |
| `dg-web/src/main/resources/configs/` | 删除 | 29 个内置 YAML |
| `dg-web/frontend/src/js/views/tasks.js` | 修改 | 去徽章/只读态、fileName 字段、删 skipped 死代码 |
| `README.md`（仓库根） | 修改 | 清理"内置/自定义任务"过时描述 |
| `dg-web/frontend/src/css/style.css` | 修改 | 删 builtin 徽章样式 |
| `dg-web/frontend/src/js/lib/yaml-editor.js`、`docs/config-guide.md` | 修改 | 文档说明 |
| `dg-ai/.../client/DgWebClient.java` | 核对/微调 | 请求字段适配 |
| `dg-ai/.../prompt/SystemPrompt.java` | 修改 | id/name 不再必填的说明 |
| `dg-web/src/test/.../service/TaskConfigServiceValidateYamlTest.java` | 修改 | 适配新构造器 |
| `dg-web/src/test/.../service/TaskConfigServiceListIntegrationTest.java` | 重写或删除 | 旧前提（目录扫描/skipped）作废 |
| `dg-web/src/test/.../controller/TaskConfigControllerTest.java` | 重写 | @WebMvcTest 适配新 DTO/404 语义 |
| `dg-web/src/test/.../storage/SqliteSchemaInitializerTest.java` | 修改 | 断言 tasks 存在、task_schedules 被删 |
| 各 `*Test.java` | 修改/重写 | 适配新行为 |

---

## Task 1: dg-core 放宽 YamlConfigLoader 的 name/id 必填

**Files:**
- Modify: `dg-core/src/main/java/com/datagenerator/core/model/YamlConfigLoader.java:57-68`
- Test: `dg-core/src/test/java/com/datagenerator/core/model/YamlConfigLoaderTest.java`

- [ ] **Step 1: 写失败测试**

在 `YamlConfigLoaderTest` 中新增用例（已有测试类内追加）：

```java
    @Test
    void loadTaskConfigFromContent_withoutIdAndName_accepts() {
        String yaml = """
                tables:
                  - name: t1
                    count: 1
                    writer:
                      type: csv
                      path: ./out
                """;

        TaskConfig taskConfig = loader.loadTaskConfigFromContent(yaml);

        assertThat(taskConfig.getId()).isNull();
        assertThat(taskConfig.getName()).isNull();
        assertThat(taskConfig.getTables()).hasSize(1);
    }

    @Test
    void loadTaskConfigFromContent_withIdAndName_keepsThem() {
        String yaml = """
                id: my_id
                name: 我的任务
                tables:
                  - name: t1
                    count: 1
                    writer:
                      type: csv
                      path: ./out
                """;

        TaskConfig taskConfig = loader.loadTaskConfigFromContent(yaml);

        assertThat(taskConfig.getId()).isEqualTo("my_id");
        assertThat(taskConfig.getName()).isEqualTo("我的任务");
    }
```

（注：测试夹具的 writer 结构请参照该测试类已有用例的 writer 写法，保证 `WriterConfigResolver` 校验通过。已核实表级 writer 仅校验 `type` 非空，`path: ./out` 可通过。）

同时删除/改写 `YamlConfigLoaderTest.java:140-197` 的 3 个反向用例——`loadTaskConfig_missingNameAndId_throws`、`loadTaskConfig_missingName_throws`、`loadTaskConfig_missingId_throws`（断言缺 name/id 抛 `ConfigLoadException`，放宽后校验逻辑已消失，断言对象不存在，必须删除或改为正向断言）。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl dg-core -Dtest=YamlConfigLoaderTest`
Expected: 新用例 FAIL，报 `ConfigLoadException: Task config must define 'name'`

- [ ] **Step 3: 修改实现**

`YamlConfigLoader.loadTaskConfigFromRoot`（第 57-68 行）改为：

```java
    private TaskConfig loadTaskConfigFromRoot(Map<String, Object> root) {
        TaskConfig taskConfig = new TaskConfig();
        // name/id 为可选字段：任务元数据由 dg-web 主表管理，YAML 可不再携带
        taskConfig.setName(YamlMappingUtils.asString(root.get("name")));
        taskConfig.setId(YamlMappingUtils.asString(root.get("id")));
        Object constraintsValue = root.get("constraints");
        ...
```

即删除第 59-66 行对 name/id 的必填校验（`if (name == null || name.isBlank()) throw ...` 与 id 同理的两段），保留 `setName/setId` 赋值。其余逻辑不变。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl dg-core`
Expected: 全绿（包括原有用例与新增用例）

- [ ] **Step 5: Commit（需用户授权）**

```bash
git add dg-core/src/main/java/com/datagenerator/core/model/YamlConfigLoader.java dg-core/src/test/java/com/datagenerator/core/model/YamlConfigLoaderTest.java
git commit -m "feat(core): 放宽任务 YAML 的 name/id 必填校验，元数据改由主表管理"
```

---

## Task 2: 存储层——tasks 表 + TaskRepository（新增，暂不动旧类）

**Files:**
- Create: `dg-web/src/main/java/com/datagenerator/web/storage/TaskRepository.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/storage/SqliteSchemaInitializer.java:13-49`
- Test: `dg-web/src/test/java/com/datagenerator/web/storage/TaskRepositoryTest.java`

- [ ] **Step 1: 写失败测试**

新建 `TaskRepositoryTest`（参照 `TaskRunRepositoryTest` 的 `SqliteTestSupport.createInMemoryJdbcTemplate()`）：

```java
package com.datagenerator.web.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRepositoryTest {

    private TaskRepository repository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        repository = new TaskRepository(jdbcTemplate);
    }

    @Test
    void insert_and_findByFileName_returnsTask() {
        repository.insert(new TaskRepository.TaskRecord(
                "taskabc123", "taskabc123", "演示任务", false, null,
                "2026-09-02T10:00:00Z", null));

        var found = repository.findByFileName("taskabc123");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("taskabc123");
        assertThat(found.get().displayName()).isEqualTo("演示任务");
        assertThat(found.get().scheduleEnabled()).isFalse();
    }

    @Test
    void updateSchedule_updatesScheduleFieldsOnly() {
        repository.insert(new TaskRepository.TaskRecord(
                "taskabc123", "taskabc123", "演示任务", false, null,
                "2026-09-02T10:00:00Z", null));

        repository.updateSchedule("taskabc123", true, "0 0 2 * * ?", "2026-09-02T11:00:00Z");

        var found = repository.findByFileName("taskabc123").orElseThrow();
        assertThat(found.scheduleEnabled()).isTrue();
        assertThat(found.scheduleCron()).isEqualTo("0 0 2 * * ?");
        assertThat(found.displayName()).isEqualTo("演示任务");
    }

    @Test
    void listPage_withKeywordAndPagination_returnsFilteredPage() {
        repository.insert(record("t1", "用户表任务"));
        repository.insert(record("t2", "订单表任务"));
        repository.insert(record("t3", "订单明细"));

        List<TaskRepository.TaskRecord> page = repository.listPage(0, 2, "订单");
        long total = repository.count("订单");

        assertThat(total).isEqualTo(2);
        assertThat(page).hasSize(2);
        assertThat(page).extracting(TaskRepository.TaskRecord::fileName)
                .containsExactlyInAnyOrder("t2", "t3");
    }

    @Test
    void listPage_withoutKeyword_ordersByCreatedAtDesc() {
        repository.insert(record("t1", "旧任务"));
        repository.insert(record("t2", "新任务"));

        List<TaskRepository.TaskRecord> page = repository.listPage(0, 10, null);

        assertThat(page).extracting(TaskRepository.TaskRecord::fileName)
                .containsExactly("t2", "t1");
    }

    @Test
    void findAllEnabledSchedules_returnsOnlyEnabledWithCron() {
        repository.insert(new TaskRepository.TaskRecord(
                "t1", "t1", "a", true, "0 0 1 * * ?", "2026-09-02T10:00:00Z", null));
        repository.insert(new TaskRepository.TaskRecord(
                "t2", "t2", "b", false, "0 0 2 * * ?", "2026-09-02T10:00:00Z", null));
        repository.insert(new TaskRepository.TaskRecord(
                "t3", "t3", "c", true, null, "2026-09-02T10:00:00Z", null));

        var schedules = repository.findAllEnabledSchedules();

        assertThat(schedules).extracting(TaskRepository.TaskRecord::fileName)
                .containsExactly("t1");
    }

    @Test
    void deleteByFileName_removesRow() {
        repository.insert(record("t1", "任务"));

        repository.deleteByFileName("t1");

        assertThat(repository.findByFileName("t1")).isEmpty();
    }

    @Test
    void existsByFileName_returnsTrueOnlyForExisting() {
        repository.insert(record("t1", "任务"));

        assertThat(repository.existsByFileName("t1")).isTrue();
        assertThat(repository.existsByFileName("t9")).isFalse();
    }

    private static TaskRepository.TaskRecord record(String fileName, String displayName) {
        return new TaskRepository.TaskRecord(
                fileName, fileName, displayName, false, null,
                "2026-09-02T10:00:00Z", null);
    }
}
```

（注：`SqliteTestSupport` 在 `dg-web/src/test/java/com/datagenerator/web/storage/SqliteTestSupport.java`，其 `createInMemoryJdbcTemplate()` 会调用 `SqliteSchemaInitializer.initialize`，因此新表会随初始化自动创建。若 createInMemoryJdbcTemplate 用的是自定义初始化而非 SqliteSchemaInitializer，请同步补充建表语句。）

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl dg-web -Dtest=TaskRepositoryTest`
Expected: 编译失败（TaskRepository 不存在）或建表失败

- [ ] **Step 3: 修改 SqliteSchemaInitializer**

在 `initialize`（第 39-48 行区域）替换 task_schedules 建表段为：

```java
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    file_name TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    schedule_enabled INTEGER NOT NULL DEFAULT 0,
                    schedule_cron TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_tasks_created_at
                ON tasks(created_at)
                """);
```

同时删除第 40-46 行的 `task_schedules` 建表语句、第 48 行 `ensureColumn(jdbcTemplate, "task_schedules", "created_at", "TEXT")`。**本任务暂不执行 DROP**（等消费者迁移完成后再 DROP，避免过早破坏；`ensureColumn(jdbcTemplate, "task_runs", "trigger_source", "TEXT")` 保留）。

- [ ] **Step 4: 新建 TaskRepository**

```java
package com.datagenerator.web.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 任务主表（tasks）仓储：任务元数据与调度字段的持久化 */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbcTemplate;

    public TaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record TaskRecord(
            String id,
            String fileName,
            String displayName,
            boolean scheduleEnabled,
            String scheduleCron,
            String createdAt,
            String updatedAt) {
    }

    public void insert(TaskRecord task) {
        jdbcTemplate.update("""
                INSERT INTO tasks (id, file_name, display_name, schedule_enabled, schedule_cron, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                task.id(),
                task.fileName(),
                task.displayName(),
                task.scheduleEnabled() ? 1 : 0,
                task.scheduleCron(),
                task.createdAt(),
                task.updatedAt());
    }

    public void update(String fileName, String displayName, String updatedAt) {
        jdbcTemplate.update("""
                UPDATE tasks SET display_name = ?, updated_at = ? WHERE file_name = ?
                """,
                displayName, updatedAt, fileName);
    }

    public void updateSchedule(String fileName, boolean enabled, String cron, String updatedAt) {
        jdbcTemplate.update("""
                UPDATE tasks SET schedule_enabled = ?, schedule_cron = ?, updated_at = ? WHERE file_name = ?
                """,
                enabled ? 1 : 0, cron, updatedAt, fileName);
    }

    public Optional<TaskRecord> findByFileName(String fileName) {
        List<TaskRecord> results = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE file_name = ?", this::mapRow, fileName);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<TaskRecord> findById(String id) {
        List<TaskRecord> results = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE id = ?", this::mapRow, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public boolean existsByFileName(String fileName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE file_name = ?", Integer.class, fileName);
        return count != null && count > 0;
    }

    public List<TaskRecord> listPage(int offset, int limit, String nameKeyword) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS);
        List<Object> args = new java.util.ArrayList<>();
        if (nameKeyword != null && !nameKeyword.isBlank()) {
            sql.append(" WHERE display_name LIKE ?");
            args.add("%" + nameKeyword.trim() + "%");
        }
        sql.append(" ORDER BY created_at DESC, file_name DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), this::mapRow, args.toArray());
    }

    public long count(String nameKeyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM tasks");
        List<Object> args = new java.util.ArrayList<>();
        if (nameKeyword != null && !nameKeyword.isBlank()) {
            sql.append(" WHERE display_name LIKE ?");
            args.add("%" + nameKeyword.trim() + "%");
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** 全部启用且配置了 cron 的任务，供启动时注册调度 */
    public List<TaskRecord> findAllEnabledSchedules() {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE schedule_enabled = 1 AND schedule_cron IS NOT NULL ORDER BY file_name",
                this::mapRow);
    }

    public void deleteByFileName(String fileName) {
        jdbcTemplate.update("DELETE FROM tasks WHERE file_name = ?", fileName);
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, file_name, display_name, schedule_enabled, schedule_cron, created_at, updated_at
            FROM tasks
            """;

    private TaskRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRecord(
                rs.getString("id"),
                rs.getString("file_name"),
                rs.getString("display_name"),
                rs.getInt("schedule_enabled") != 0,
                rs.getString("schedule_cron"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
```

- [ ] **Step 5: 同步处置受 DDL 变更影响的存量测试（本任务内，否则 Task 4 全量门禁必红）**

a) 删除 `dg-web/src/test/java/com/datagenerator/web/storage/TaskScheduleRepositoryTest.java`（其 upsert 依赖已被删除的 task_schedules DDL；主类 TaskScheduleRepository 待 Task 5 删除）。

b) 修改 `dg-web/src/test/java/com/datagenerator/web/storage/SqliteSchemaInitializerTest.java`：将断言 `task_schedules` 表存在的用例改为断言——`tasks` 表存在且含 `id/file_name/display_name/schedule_enabled/schedule_cron/created_at/updated_at` 列（PRAGMA table_info 查询）；`task_schedules` 表不存在（sqlite_master 中查不到）。内存库新建即无旧表，断言在删除 DDL 后立即成立。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn test -pl dg-web -Dtest=TaskRepositoryTest,SqliteSchemaInitializerTest`
Expected: PASS

- [ ] **Step 7: Commit（需用户授权）**

```bash
git add dg-web/src/main/java/com/datagenerator/web/storage/TaskRepository.java dg-web/src/main/java/com/datagenerator/web/storage/SqliteSchemaInitializer.java dg-web/src/test/java/com/datagenerator/web/storage/
git commit -m "feat(web): 新增任务主表 tasks 与 TaskRepository，替换 task_schedules 建表"
```

---

## Task 3: DTO 改造 + 路径工具类

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/dto/TaskConfigRequest.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/dto/TaskConfigResponse.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/dto/TaskScheduleResponse.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/dto/TaskConfigListResponse.java`
- Delete: `dg-web/src/main/java/com/datagenerator/web/dto/TaskConfigSkipInfo.java`
- Create: `dg-web/src/main/java/com/datagenerator/web/service/TaskConfigPaths.java`

**说明：** 本任务只改 DTO 与新增工具类，不迁移服务层；因此 Task 3 完成后 `dg-web` 会**编译失败**（服务层仍引用旧字段）。这是计划内的中间态——Task 4 立即完成服务层迁移。若要保持每步可编译，可将 Task 3 与 Task 4 合并执行；建议合并为一次提交。

- [ ] **Step 1: 改 TaskConfigRequest**

```java
package com.datagenerator.web.dto;

public class TaskConfigRequest {

    /** 配置文件名（ASCII，新建时可选；未指定时使用自动生成的 task id）。 */
    private String fileName;
    /** 任务显示名称，存主表 display_name。 */
    private String displayName;
    private String content;
    private TaskScheduleRequest schedule;

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public TaskScheduleRequest getSchedule() {
        return schedule;
    }

    public void setSchedule(TaskScheduleRequest schedule) {
        this.schedule = schedule;
    }
}
```

（字段 `name` 重命名为 `fileName`；getter/setter 同步。其他不变。）

- [ ] **Step 2: 改 TaskConfigResponse**

删除 `builtin`、`readOnly` 字段及其 getter/setter；删除带 `boolean builtin, boolean readOnly` 的两个构造器，替换为：

```java
    public TaskConfigResponse(
            String fileName,
            String path,
            String id,
            String name,
            String content) {
        this.fileName = fileName;
        this.path = path;
        this.id = id;
        this.name = name;
        this.content = content;
    }
```

其余字段（fileName/name/path/id/content/schedule/createdAt）不变。

- [ ] **Step 3: 改 TaskScheduleResponse**

删除 `editable` 字段、getter/setter 与构造器参数，替换为：

```java
    public TaskScheduleResponse(boolean enabled, String cron, String nextRunAt) {
        this.enabled = enabled;
        this.cron = cron;
        this.nextRunAt = nextRunAt;
    }
```

- [ ] **Step 4: 改 TaskConfigListResponse**

```java
package com.datagenerator.web.dto;

import java.util.List;

/** 任务配置列表响应：任务条目 + 分页元数据 */
public record TaskConfigListResponse(
        List<TaskConfigResponse> items,
        long total,
        int page,
        int size) {
}
```

删除文件 `TaskConfigSkipInfo.java`。

- [ ] **Step 5: 新建 TaskConfigPaths**

```java
package com.datagenerator.web.service;

/** 任务配置路径与文件名的相互转换工具 */
public final class TaskConfigPaths {

    public static final String TASK_CONFIGS_DIR = "task-configs";

    private TaskConfigPaths() {
    }

    /** 文件名（不含扩展名）→ 配置路径（如 demo → task-configs/demo.yaml） */
    public static String toConfigPath(String fileName) {
        String normalized = fileName.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(TASK_CONFIGS_DIR + "/")) {
            normalized = normalized.substring(TASK_CONFIGS_DIR.length() + 1);
        }
        if (normalized.endsWith(".yaml") || normalized.endsWith(".yml")) {
            return TASK_CONFIGS_DIR + "/" + normalized;
        }
        return TASK_CONFIGS_DIR + "/" + normalized + ".yaml";
    }

    /** 配置路径 → 文件名（task-configs/demo.yaml → demo） */
    public static String toFileName(String configPath) {
        String normalized = configPath.trim().replace('\\', '/');
        if (normalized.startsWith(TASK_CONFIGS_DIR + "/")) {
            normalized = normalized.substring(TASK_CONFIGS_DIR.length() + 1);
        }
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.replaceFirst("\\.ya?ml$", "");
    }
}
```

- [ ] **Step 6: 新增 TaskConfigPathsTest**

`dg-web/src/test/java/com/datagenerator/web/service/TaskConfigPathsTest.java`：

```java
package com.datagenerator.web.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskConfigPathsTest {

    @Test
    void toConfigPath_appendsDirAndExtension() {
        assertThat(TaskConfigPaths.toConfigPath("demo")).isEqualTo("task-configs/demo.yaml");
        assertThat(TaskConfigPaths.toConfigPath("demo.yaml")).isEqualTo("task-configs/demo.yaml");
        assertThat(TaskConfigPaths.toConfigPath("task-configs/demo")).isEqualTo("task-configs/demo.yaml");
    }

    @Test
    void toFileName_extractsBasename() {
        assertThat(TaskConfigPaths.toFileName("task-configs/demo.yaml")).isEqualTo("demo");
        assertThat(TaskConfigPaths.toFileName("task-configs/demo")).isEqualTo("demo");
        assertThat(TaskConfigPaths.toFileName("demo.yaml")).isEqualTo("demo");
    }
}
```

**注意：** 本任务完成后 dg-web 整体**编译失败**（服务层仍引用旧 DTO 字段），属计划内中间态——**不单独运行测试、不单独提交**；`TaskConfigPathsTest` 的运行验证并入 Task 4 Step 4。

---

## Task 4: 服务层迁移（TaskConfigService 重写 + TaskScheduleService 重写 + Manager/Controller 适配）

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/service/TaskConfigService.java`（整体重写）
- Modify: `dg-web/src/main/java/com/datagenerator/web/service/TaskScheduleService.java`（整体重写）
- Modify: `dg-web/src/main/java/com/datagenerator/web/service/TaskScheduleManager.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/controller/TaskConfigController.java`
- Delete: `dg-web/src/main/java/com/datagenerator/web/exception/ReadOnlyScheduleException.java`
- Test: 重写 `TaskConfigServiceTest`、`TaskScheduleServiceTest`、`TaskScheduleManagerTest`

### 4.0 新建 TaskConfigNotFoundException + GlobalExceptionHandler 映射

新建 `dg-web/src/main/java/com/datagenerator/web/exception/TaskConfigNotFoundException.java`（参照 `TaskRunNotFoundException` 的写法）：

```java
package com.datagenerator.web.exception;

public class TaskConfigNotFoundException extends RuntimeException {

    public TaskConfigNotFoundException(String message) {
        super(message);
    }
}
```

修改 `GlobalExceptionHandler.java`：
- 在 `TaskRunNotFoundException` 的 handler（52-60 行）旁新增同构 handler，把 `TaskConfigNotFoundException` 映射为 404
- 删除 `ReadOnlyScheduleException` 的 `@ExceptionHandler`（80 行起）

**404 语义（按规格 §3/§7）：** `get/update/delete` 表无行、以及表有行但文件缺失，一律抛 `TaskConfigNotFoundException`（消息区分 "not found: xxx" 与 "file missing: xxx"），返回 404。

### 4.1 TaskConfigService 重写

新实现要点（完整代码）：

```java
package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskConfigListResponse;
import com.datagenerator.web.dto.TaskConfigRequest;
import com.datagenerator.web.dto.TaskConfigResponse;
import com.datagenerator.web.dto.TaskScheduleRequest;
import com.datagenerator.web.dto.TaskConfigValidationResponse;
import com.datagenerator.web.exception.TaskConfigNotFoundException;
import com.datagenerator.web.storage.TaskRepository;
import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.TaskConfig;
import com.datagenerator.core.model.YamlConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskConfigService {

    private static final Logger log = LoggerFactory.getLogger(TaskConfigService.class);
    private static final int MAX_LIST_SIZE = 200;

    private final ConfigPathResolver pathResolver;
    private final YamlConfigLoader configLoader;
    private final TaskRepository taskRepository;
    private final TaskScheduleService scheduleService;
    private final TaskScheduleManager scheduleManager;
    private final TaskRunQueueExecutor scheduleExecutor;

    public TaskConfigService(
            ConfigPathResolver pathResolver,
            TaskRepository taskRepository,
            TaskScheduleService scheduleService,
            @Lazy TaskScheduleManager scheduleManager,
            TaskRunQueueExecutor scheduleExecutor) {
        this.pathResolver = pathResolver;
        this.configLoader = new YamlConfigLoader(pathResolver);
        this.taskRepository = taskRepository;
        this.scheduleService = scheduleService;
        this.scheduleManager = scheduleManager;
        this.scheduleExecutor = scheduleExecutor;
    }

    public TaskConfigValidationResponse validateYaml(String yaml) {
        try {
            configLoader.loadTaskConfigFromContent(stripMetaFields(yaml));
            return TaskConfigValidationResponse.ok();
        } catch (ConfigLoadException exception) {
            return TaskConfigValidationResponse.fail(List.of(exception.getMessage()));
        }
    }

    public TaskConfigListResponse list() {
        return list(null, null, null);
    }

    public TaskConfigListResponse list(String nameKeyword) {
        return list(nameKeyword, null, null);
    }

    /** 分页查询任务列表；page/size 任一为空时返回全量（兼容无分页调用方） */
    public TaskConfigListResponse list(String nameKeyword, Integer page, Integer size) {
        if (page == null || size == null) {
            List<TaskConfigResponse> all = taskRepository.listPage(0, MAX_LIST_SIZE, nameKeyword).stream()
                    .map(this::toResponse)
                    .toList();
            return new TaskConfigListResponse(all, all.size(), 1, all.size());
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_LIST_SIZE);
        long total = taskRepository.count(nameKeyword);
        List<TaskConfigResponse> items = taskRepository.listPage((safePage - 1) * safeSize, safeSize, nameKeyword)
                .stream()
                .map(this::toResponse)
                .toList();
        return new TaskConfigListResponse(items, total, safePage, safeSize);
    }

    public TaskConfigResponse get(String name) {
        TaskRepository.TaskRecord task = taskRepository.findByFileName(name)
                .orElseThrow(() -> new TaskConfigNotFoundException("Task config not found: " + name));
        String configPath = TaskConfigPaths.toConfigPath(name);
        String content = readContent(configPath);
        configLoader.loadTaskConfigFromContent(content);
        return toResponse(task, content);
    }

    public TaskConfigResponse create(TaskConfigRequest request) {
        String displayName = requireDisplayName(request.getDisplayName());
        TaskScheduleRequest normalizedSchedule = normalizeScheduleIfPresent(request.getSchedule());
        String content = stripMetaFields(requireContent(request.getContent()));
        validateContent(content);
        String taskId = generateUniqueTaskId();
        String fileName = resolveCreateFileName(request, taskId);
        String configPath = TaskConfigPaths.toConfigPath(fileName);
        if (taskRepository.existsByFileName(fileName)) {
            throw new IllegalArgumentException("Task config already exists: " + fileName);
        }
        String now = Instant.now().toString();
        writeContent(configPath, content);
        try {
            taskRepository.insert(new TaskRepository.TaskRecord(
                    taskId, fileName, displayName, false, null, now, null));
            applySchedule(configPath, normalizedSchedule);
            return toResponse(taskRepository.findByFileName(fileName).orElseThrow(),
                    content);
        } catch (RuntimeException exception) {
            rollbackDefinition(configPath);
            throw exception;
        }
    }

    public TaskConfigResponse update(String name, TaskConfigRequest request) {
        taskRepository.findByFileName(name)
                .orElseThrow(() -> new TaskConfigNotFoundException("Task config not found: " + name));
        String displayName = requireDisplayName(request.getDisplayName());
        TaskScheduleRequest normalizedSchedule = normalizeScheduleIfPresent(request.getSchedule());
        String content = stripMetaFields(requireContent(request.getContent()));
        validateContent(content);
        String configPath = TaskConfigPaths.toConfigPath(name);
        writeContent(configPath, content);
        taskRepository.update(name, displayName, Instant.now().toString());
        applySchedule(configPath, normalizedSchedule);
        return toResponse(taskRepository.findByFileName(name).orElseThrow(), content);
    }

    /** 以表行为准：表行存在即可删除，文件缺失不阻塞 */
    public void delete(String name) {
        taskRepository.findByFileName(name)
                .orElseThrow(() -> new TaskConfigNotFoundException("Task config not found: " + name));
        String configPath = TaskConfigPaths.toConfigPath(name);
        scheduleManager.cancel(configPath);
        scheduleExecutor.clearQueue(configPath);
        taskRepository.deleteByFileName(name);
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile != null) {
            try {
                Files.deleteIfExists(overlayFile);
            } catch (IOException exception) {
                log.warn("删除任务配置文件失败 {}: {}", overlayFile, exception.getMessage());
            }
        }
    }

    private TaskConfigResponse toResponse(TaskRepository.TaskRecord task) {
        return toResponse(task, null);
    }

    private TaskConfigResponse toResponse(TaskRepository.TaskRecord task, String content) {
        TaskConfigResponse response = new TaskConfigResponse(
                task.fileName(),
                TaskConfigPaths.toConfigPath(task.fileName()),
                task.id(),
                task.displayName(),
                content);
        response.setSchedule(scheduleService.resolveSchedule(TaskConfigPaths.toConfigPath(task.fileName())));
        response.setCreatedAt(task.createdAt());
        return response;
    }

    private void validateContent(String content) {
        Map<?, ?> root = parseRootMapping(content);
        if (root.containsKey("schedule")) {
            throw new IllegalArgumentException(
                    "任务 YAML 不允许包含 schedule 块，调度请通过 schedule 接口或请求字段配置");
        }
        configLoader.loadTaskConfigFromContent(content);
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Task config content is required");
        }
        return content;
    }

    /** 剥离 YAML 中的 id/name 元数据字段（元数据以主表为准） */
    private String stripMetaFields(String content) {
        Map<String, Object> root = toMutableRoot(parseRootMapping(content));
        root.remove("id");
        root.remove("name");
        return new Yaml().dump(root);
    }

    private Map<?, ?> parseRootMapping(String content) {
        Object loaded = new Yaml().load(content);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Task config YAML must be a mapping");
        }
        return root;
    }

    private Map<String, Object> toMutableRoot(Map<?, ?> root) {
        Map<String, Object> mutable = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            mutable.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return mutable;
    }

    private TaskScheduleRequest normalizeScheduleIfPresent(TaskScheduleRequest schedule) {
        if (schedule == null) {
            return null;
        }
        return scheduleService.validateAndNormalize(schedule);
    }

    private void applySchedule(String configPath, TaskScheduleRequest normalizedSchedule) {
        if (normalizedSchedule == null) {
            return;
        }
        scheduleService.persistSchedule(configPath, normalizedSchedule);
        scheduleManager.reschedule(configPath);
    }

    private void rollbackDefinition(String configPath) {
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(overlayFile);
        } catch (IOException exception) {
            log.warn("回滚任务配置文件失败 {}: {}", overlayFile, exception.getMessage());
        }
    }

    private String resolveCreateFileName(TaskConfigRequest request, String taskId) {
        if (request.getFileName() != null && !request.getFileName().isBlank()) {
            String explicitName = request.getFileName().trim();
            validateAsciiFileName(explicitName);
            return explicitName;
        }
        return taskId;
    }

    private void validateAsciiFileName(String fileName) {
        if (!fileName.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "Task config file name must use ASCII letters, digits, underscore, hyphen: " + fileName);
        }
    }

    private String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Task config display name is required");
        }
        return displayName.trim();
    }

    private String generateUniqueTaskId() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String id = "task" + suffix;
            if (taskRepository.findById(id).isEmpty() && !taskRepository.existsByFileName(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Failed to generate unique task config id");
    }

    private String readContent(String configPath) {
        Path overlayFile = requireOverlayFile(configPath);
        if (!Files.isRegularFile(overlayFile)) {
            throw new TaskConfigNotFoundException("Task config file missing: " + configPath);
        }
        try {
            return Files.readString(overlayFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to read task config: " + configPath, exception);
        }
    }

    private void writeContent(String configPath, String content) {
        Path overlayFile = requireOverlayFile(configPath);
        try {
            Files.createDirectories(overlayFile.getParent());
            Files.writeString(overlayFile, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to write task config: " + configPath, exception);
        }
    }

    private Path requireOverlayFile(String configPath) {
        Path overlayRoot = pathResolver.writableOverlay();
        if (overlayRoot == null) {
            throw new IllegalStateException("Writable config directory is not configured");
        }
        return overlayRoot.resolve(configPath).normalize();
    }
}
```

删除的旧逻辑：`isBuiltin`、`listIncludedTaskRelativePaths`、`isListedTaskPath`、`compareForList`、`parseCreatedAt`、`resolveCreatedAt`、`readFileCreationTime`、`assignGeneratedId`、`injectDisplayName`、`stripNameFromContent`、`idExists`、`validateIdUnique`、`requireId`、`validateIdFormat`、`validateIdFormatQuiet`、`exists`（多来源存在性）、`toDefinitionName`、`toBasename`、`validateName`。`validateYaml` 现在也剥离 id/name 后校验（保证与落盘一致）。

### 4.2 TaskScheduleService 重写

```java
package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskScheduleRequest;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.exception.TaskConfigNotFoundException;
import com.datagenerator.web.storage.TaskRepository;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class TaskScheduleService {

    private final TaskRepository taskRepository;

    public TaskScheduleService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public TaskScheduleResponse resolveSchedule(String configPath) {
        TaskRepository.TaskRecord task = requireTask(configPath);
        return toResponse(task.scheduleEnabled(), task.scheduleCron());
    }

    public TaskScheduleRequest validateAndNormalize(TaskScheduleRequest request) {
        // 原逻辑保留：enabled 时 cron 必填且合法；disabled 时 cron 可空但若给出须合法
        boolean enabled = request.isEnabled();
        String cron = normalizeCron(request.getCron());

        if (enabled) {
            if (cron == null) {
                throw new IllegalArgumentException("Cron expression is required when schedule is enabled");
            }
            if (!CronExpression.isValidExpression(cron)) {
                throw new IllegalArgumentException("Invalid cron expression: " + cron);
            }
        } else if (cron != null && !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("Invalid cron expression: " + cron);
        }

        TaskScheduleRequest normalized = new TaskScheduleRequest();
        normalized.setEnabled(enabled);
        normalized.setCron(cron);
        return normalized;
    }

    public String computeNextRunAt(String cron) {
        // 原逻辑保留
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            return null;
        }
        CronExpression expression = CronExpression.parse(cron);
        LocalDateTime next = expression.next(LocalDateTime.now());
        if (next == null) {
            return null;
        }
        return next.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
    }

    public TaskScheduleResponse saveSchedule(String configPath, TaskScheduleRequest request) {
        requireTask(configPath);
        TaskScheduleRequest normalized = validateAndNormalize(request);
        persistSchedule(configPath, normalized);
        return toResponse(normalized.isEnabled(), normalized.getCron());
    }

    public void persistSchedule(String configPath, TaskScheduleRequest normalized) {
        taskRepository.updateSchedule(
                TaskConfigPaths.toFileName(configPath),
                normalized.isEnabled(),
                normalized.getCron(),
                Instant.now().toString());
    }

    private TaskRepository.TaskRecord requireTask(String configPath) {
        String fileName = TaskConfigPaths.toFileName(configPath);
        return taskRepository.findByFileName(fileName)
                .orElseThrow(() -> new TaskConfigNotFoundException("Task config not found: " + fileName));
    }

    private TaskScheduleResponse toResponse(boolean enabled, String cron) {
        String nextRunAt = enabled ? computeNextRunAt(cron) : null;
        return new TaskScheduleResponse(enabled, cron, nextRunAt);
    }

    private String normalizeCron(String cron) {
        // 原逻辑保留
        if (cron == null) {
            return null;
        }
        String trimmed = cron.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
```

删除：`isBuiltin`、`ReadOnlyScheduleException` 引用、`ConfigPathResolver`/`YamlConfigLoader` 依赖。

### 4.3 TaskScheduleManager 修改

- 构造器移除 `TaskConfigService definitionService` 与 `TaskScheduleRepository scheduleRepository`，改为注入 `TaskRepository`：

```java
    public TaskScheduleManager(
            ThreadPoolTaskScheduler scheduler,
            TaskScheduleService scheduleService,
            TaskRunQueueExecutor executor,
            TaskRepository taskRepository) {
        this.scheduler = scheduler;
        this.scheduleService = scheduleService;
        this.executor = executor;
        this.taskRepository = taskRepository;
    }
```

- `reloadAll()` 改为：

```java
    public void reloadAll() {
        futures.values().forEach(future -> future.cancel(false));
        futures.clear();

        for (TaskRepository.TaskRecord task : taskRepository.findAllEnabledSchedules()) {
            reschedule(TaskConfigPaths.toConfigPath(task.fileName()));
        }
    }
```

- `reschedule(String configPath)` 改为：

```java
    public void reschedule(String configPath) {
        cancel(configPath);
        TaskScheduleResponse schedule = scheduleService.resolveSchedule(configPath);
        if (!schedule.isEnabled() || schedule.getCron() == null) {
            return;
        }
        if (!CronExpression.isValidExpression(schedule.getCron())) {
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(
                () -> fireScheduled(configPath),
                new CronTrigger(schedule.getCron()));
        futures.put(configPath, future);
    }
```

- 删除旧 `reschedule(configPath, builtin)` 重载与 `deleteOrphans` 调用；`cancel`/`fireScheduled` 不变。

### 4.4 TaskConfigController 修改

- `getSchedule`/`updateSchedule` 改为**直接以行定位，不依赖 `taskConfigService.get(name)`**（僵尸行文件缺失时调度接口仍应可用）：

```java
    @GetMapping("/{name}/schedule")
    public TaskScheduleResponse getSchedule(@PathVariable("name") String name) {
        return taskScheduleService.resolveSchedule(TaskConfigPaths.toConfigPath(name));
    }

    @PutMapping("/{name}/schedule")
    public TaskScheduleResponse updateSchedule(
            @PathVariable("name") String name,
            @RequestBody TaskScheduleRequest request) {
        String configPath = TaskConfigPaths.toConfigPath(name);
        TaskScheduleResponse saved = taskScheduleService.saveSchedule(configPath, request);
        scheduleManager.reschedule(configPath);
        return saved;
    }
```

- 其余端点签名不变（DTO 类型自动适配）。检查 import。

### 4.5 删除 ReadOnlyScheduleException

`dg-web/src/main/java/com/datagenerator/web/exception/ReadOnlyScheduleException.java` 整体删除。其 `@ExceptionHandler` 已在 4.0 中从 `GlobalExceptionHandler` 移除；全局搜索其余引用（测试等），一并清除。

### 4.6 测试重写

**TaskConfigServiceTest**（重写要点，参照原测试文件的 mock 风格——原构造依赖改为 mock `TaskRepository` + 真实内存 SQLite 更佳。建议：用 `SqliteTestSupport.createInMemoryJdbcTemplate()` 创建真实 `TaskRepository`，`TaskScheduleService` 也用真实 repository 构造，`TaskScheduleManager`/`TaskRunQueueExecutor` 用 mock；`ConfigPathResolver` 用 `ConfigPathResolver.fromSetting("classpath:configs", getClass().getClassLoader(), tempDir)`，其中 tempDir 为 `@TempDir Path`。）必测用例：

1. `list_withTasks_returnsPageOrderedByCreatedAt`
2. `get_existing_returnsDisplayNameAndContent`（含 YAML 无 id/name）
3. `get_missingFile_throwsNotFound`（表有行但文件缺失 → `TaskConfigNotFoundException`）
4. `create_validRequest_writesFileAndInsertsRow`（断言：表行存在、文件存在、文件内容不含 id/name/schedule）
5. `create_yamlWithIdAndName_stripsThem`
6. `create_yamlWithScheduleBlock_rejects`
7. `create_withScheduleRequest_persistsScheduleFields`
8. `create_duplicateFileName_rejects`
9. `update_existing_updatesDisplayNameAndFile`
10. `update_missing_throwsNotFound`
11. `delete_existing_removesRowAndFile`
12. `delete_missingFile_stillRemovesRow`（僵尸行可删）
13. `delete_missing_throwsNotFound`

**其余存量测试文件处置：**

- `TaskConfigServiceValidateYamlTest`：适配新构造器签名（去掉 `TaskScheduleRepository` 参数，按新签名注入 `TaskRepository` 等）；validateYaml 断言若涉及 id/name 必填，改为允许缺失
- `TaskConfigServiceListIntegrationTest`：旧前提（classpath 内置 + overlay 合并列表、`.skipped()` 断言）整体作废——**重写为表驱动列表测试**（真实内存 SQLite `TaskRepository` + `@TempDir` overlay，断言分页/过滤/排序），或删除（用例已由 `TaskConfigServiceTest` 覆盖）
- `TaskConfigControllerTest`（`@WebMvcTest`）：重写——新 DTO 构造器、单参 `resolveSchedule` stub、`TaskConfigNotFoundException`→404 断言（`$.builtin`/`$.readOnly`/403 只读语义断言全部移除）
- `SqliteSchemaInitializerTest`：已在 Task 2 Step 5 处置，此处无动作

**TaskScheduleServiceTest**（重写）：用真实 `TaskRepository` + 内存 SQLite。

1. `resolveSchedule_noRow_throws`
2. `resolveSchedule_withRow_returnsEnabledCronAndNextRunAt`
3. `saveSchedule_valid_updatesTasksRow`
4. `saveSchedule_enabledWithoutCron_rejects`
5. `saveSchedule_invalidCron_rejects`

**TaskScheduleManagerTest**（重写）：mock `TaskScheduleService`、`ThreadPoolTaskScheduler`、`TaskRunQueueExecutor`、`TaskRepository`。

1. `reloadAll_enabledTask_registersSchedule`（`findAllEnabledSchedules` 返回一条，verify `scheduler.schedule`）
2. `reschedule_disabledTask_doesNotRegister`
3. `cancel_existingSchedule_cancelsFuture`

- [ ] **Step 1: 先写/改上述测试文件（4.6 全部清单，预期编译失败）**

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -pl dg-web -Dtest=TaskConfigServiceTest,TaskScheduleServiceTest,TaskScheduleManagerTest,TaskConfigPathsTest,TaskConfigServiceValidateYamlTest,TaskConfigControllerTest`
Expected: 编译失败（旧字段/构造器已不存在或新类未就位）

- [ ] **Step 3: 实施 4.0-4.5 的代码修改**

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -pl dg-web`
Expected: 全绿（含 `TaskConfigServiceListIntegrationTest` 的重写版本或已删除）

- [ ] **Step 5: 提交 Task 3 + Task 4（需用户授权）**

```bash
git add dg-web/src/main/java/com/datagenerator/web/dto/ dg-web/src/main/java/com/datagenerator/web/service/ dg-web/src/main/java/com/datagenerator/web/controller/TaskConfigController.java dg-web/src/main/java/com/datagenerator/web/exception/ dg-web/src/test/java/com/datagenerator/web/service/
git commit -m "refactor(web): 任务 CRUD 与调度迁移至 tasks 主表，移除内置任务与只读概念"
```

---

## Task 5: 收尾清理——删除 TaskScheduleRepository 与 DROP task_schedules

**Files:**
- Delete: `dg-web/src/main/java/com/datagenerator/web/storage/TaskScheduleRepository.java`
- Modify: `dg-web/src/main/java/com/datagenerator/web/storage/SqliteSchemaInitializer.java`
- Test: `dg-web/src/test/java/com/datagenerator/web/storage/`（若存在 `TaskScheduleRepositoryTest` 一并删除）

- [ ] **Step 1: 删除 TaskScheduleRepository 主类**

（其测试已在 Task 2 Step 5 删除。）

- [ ] **Step 2: SqliteSchemaInitializer 追加 DROP**

在 `initialize` 的 tasks 建表语句之后添加：

```java
        // 旧调度表随内置任务概念一并废弃，启动时清理（无迁移，按规格丢弃旧数据）
        jdbcTemplate.execute("DROP TABLE IF EXISTS task_schedules");
```

- [ ] **Step 3: 运行确认通过**

Run: `mvn test -pl dg-web`
Expected: 全绿（此时 dg-web 所有旧引用已清除，含改版后的 SqliteSchemaInitializerTest）

- [ ] **Step 4: Commit（需用户授权）**

```bash
git add dg-web/src/main/java/com/datagenerator/web/storage/ dg-web/src/test/java/com/datagenerator/web/storage/
git commit -m "refactor(web): 删除 task_schedules 表与 TaskScheduleRepository"
```

---

## Task 6: TaskRunService 适配（查表校验 + 运行时注入 id/name）

**Files:**
- Modify: `dg-web/src/main/java/com/datagenerator/web/service/TaskRunService.java`（构造器 99-122、`loadAndApplyOverrides` 511-515、`validateConfigPath` 968-972）
- Test: `dg-web/src/test/java/com/datagenerator/web/service/TaskRunServiceTest.java`

- [ ] **Step 1: 改测试（新增用例 + 存量用例适配）**

**a) 新增用例**（`TaskRunServiceTest` 内追加）：

```java
    @Test
    void submit_withUnknownConfigPath_throwsNotFound() {
        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setConfigPath("task-configs/nonexistent.yaml");

        assertThatThrownBy(() -> taskRunService.submit(request))
                .isInstanceOf(TaskConfigNotFoundException.class)
                .hasMessageContaining("not found");
    }
```

（已核实 `TaskRunService.doSubmit`（179 行）同步调用 `validateConfigPath`，且 `TaskRunServiceTestSupport.wireEnqueueToDoSubmit` 把 `submit` 接到 `doSubmit`，本用例按预期失败/通过。）

**b) 构造器补参（第 12 参 TaskRepository）——直接 `new TaskRunService(...)` 的位置共 4 处，全部补参：**

- `TaskRunServiceTest.java:42-53`
- `TaskRunServiceAsyncTest.java:117-128`
- `TaskRunServiceWriterResolutionTest.java:29-40`
- `TaskRunServiceTestSupport.createTaskRunService:61-72`

**c) 存量用例的 configPath 预插 tasks 行**——改造后凡经 `validateConfigPath`/`loadAndApplyOverrides` 的路径都要求表行存在：

- `TaskRunServiceTest.createQueuedRun_insertsPendingWithoutExecuting` 用 `task-configs/demo.yaml` → 预插 file_name=`demo`
- `TaskRunServiceAsyncTest` 3 个用例用 `large.yaml`/`small.yaml` → 预插 file_name=`large`、`small`
- 建议在 `TaskRunServiceTestSupport.createContext` 的统一内存库中集中预插（id=file_name、display_name 同名、schedule 关闭），各测试类开箱即用

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -pl dg-web -Dtest=TaskRunServiceTest,TaskRunServiceAsyncTest,TaskRunServiceWriterResolutionTest`
Expected: 新用例 FAIL（未知任务仍可入队）

- [ ] **Step 3: 修改 TaskRunService**

a) 构造器新增 `TaskRepository taskRepository` 参数并赋值（第 99-122 行）；`TaskRunServiceTestSupport` 同步更新。同时为 `TaskRunService` 与 `TaskRunServiceTest` 添加 `import com.datagenerator.web.exception.TaskConfigNotFoundException;`。

b) `validateConfigPath`（968-972 行）改为：

```java
    private void validateConfigPath(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            throw new IllegalArgumentException("configPath is required");
        }
        String fileName = TaskConfigPaths.toFileName(configPath);
        if (!taskRepository.existsByFileName(fileName)) {
            // 按规格 §7：历史运行记录引用已删除任务时，重跑返回 404
            throw new TaskConfigNotFoundException("Task config not found: " + fileName);
        }
    }
```

c) `loadAndApplyOverrides`（511-515 行）改为：

```java
    private TaskConfig loadAndApplyOverrides(TaskRunSubmitRequest request) {
        TaskConfig taskConfig = configLoader.loadTaskConfig(request.getConfigPath());
        applyOverrides(taskConfig, request.getOverrides());
        taskRepository.findByFileName(TaskConfigPaths.toFileName(request.getConfigPath()))
                .ifPresent(task -> {
                    taskConfig.setId(task.id());
                    taskConfig.setName(task.displayName());
                });
        return taskConfig;
    }
```

（`TaskConfig` 为可变 POJO，`setId/setName` 已存在，dg-core 无需改动。）

d) 文件读取保留 `configLoader.loadTaskConfig(configPath)` 多来源读取：`ConfigPathResolver.open` 为 overlay 优先，且 Task 7 删除 classpath 内置资源后 task-configs 仅存在于 overlay，行为等效单一来源，无需改造。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -pl dg-web -Dtest=TaskRunServiceTest`
Expected: PASS

- [ ] **Step 5: 处置 EndToEndTest（preview 现在也要求任务行存在）**

`dg-web/src/test/java/com/datagenerator/web/EndToEndTest.java`（真实全上下文启动）对 `task-configs/preview_smoke.yaml`（test-resources classpath fixture）调用 `TaskRunService.preview()`，该路径从 Task 6 起必经 `validateConfigPath` 查表。处置：

- 在 preview 调用前，通过 `TaskRepository`（从 Spring 上下文获取）插入一行 `TaskRecord`（id=`preview_smoke`、file_name=`preview_smoke`、display_name=`Preview Smoke`、schedule 关闭），使 preview 正常通过；测试尾部或 @AfterEach 清理该行
- 设计后果说明（写入计划即可，无需改规格）：**preview 与运行提交一致要求任务行存在**——preview 的对象必须是主表中登记的任务，这是"任务统一由 Web 创建"的自然延伸

- [ ] **Step 6: 运行确认通过**

Run: `mvn test -pl dg-web`
Expected: 全绿（含 EndToEndTest）

- [ ] **Step 7: Commit（需用户授权）**

```bash
git add dg-web/src/main/java/com/datagenerator/web/service/TaskRunService.java dg-web/src/test/java/com/datagenerator/web/service/TaskRunServiceTest.java dg-web/src/test/java/com/datagenerator/web/service/TaskRunServiceTestSupport.java dg-web/src/test/java/com/datagenerator/web/EndToEndTest.java
git commit -m "feat(web): 运行提交校验任务主表存在性，执行前注入任务 id 与显示名"
```

---

## Task 7: 删除 classpath 内置任务资源

**Files:**
- Delete: `dg-web/src/main/resources/configs/`（整个目录，29 个 YAML）

- [ ] **Step 1: 删除目录**

```bash
git rm -r dg-web/src/main/resources/configs/
```

- [ ] **Step 2: 全局搜索残留引用**

```bash
grep -rn "classpath:configs\|configs/task-configs" --include="*.java" --include="*.yml" --include="*.md" dg-web dg-ai scripts docs 2>/dev/null | grep -v "data/configs" | head -50
```

检查结果：`application.yml`/`application-local.yml` 的 `config-dir: classpath:configs` 按规格保留；其余测试 fixture 若引用 classpath 资源则同步调整（测试改用 `@TempDir` 或内存 YAML 字符串）。`ConfigPathResolver` 在资源为空时枚举返回空（评审已确认安全）。

- [ ] **Step 3: 运行确认通过**

Run: `mvn test -pl dg-web`
Expected: 全绿

- [ ] **Step 4: Commit（需用户授权）**

```bash
git add dg-web/src/main/resources/configs/
git commit -m "chore(web): 删除 classpath 内置任务配置资源"
```

---

## Task 8: 前端适配

**Files:**
- Modify: `dg-web/frontend/src/js/views/tasks.js`
- Modify: `dg-web/frontend/src/css/style.css`
- Modify: `dg-web/frontend/src/docs/config-guide.md`
- Check: `dg-web/frontend/src/js/core/api.js`、`dg-web/frontend/src/js/lib/yaml-editor.js`

无前端测试框架，验证靠打包 + 手动检查。改动点（按行号，执行时以实际文件为准）：

- [ ] **Step 1: tasks.js 移除内置/只读逻辑**

- 第 161-163 行 `allListResult.skipped` 的提示逻辑删除（`skipped` 概念已随 API 移除，`|| []` 兜底虽不致崩溃但已成死代码）
- 第 327 行 `const isBuiltin = item.builtin === true || item.readOnly === true;` 删除
- 第 335 行 徽章渲染分支 `? '<span class="badge builtin">内置</span>' : ''` 删除
- `renderActionsCell`（350 行）移除 `isBuiltin` 参数及"查看（只读）"与"编辑"分流：统一渲染一个"编辑"按钮（删除 484 行附近的 `openDefinitionModal(fileName, item.content, true, ...)` 查看分支，保留 497 行的编辑调用）
- `openDefinitionModal`（391 行）移除 `readOnly` 参数与 398-402 行的禁用逻辑
- `applyScheduleFields`（443 行）：`editingScheduleEditable = !readOnly && sched.editable !== false;` 改为恒 `true`（或直接删除 editable 判断，输入框恒可编辑）；449-450 行的 disabled 赋值删除
- 创建/编辑提交的 payload（约 510-530 行）：请求字段 `name:` → `fileName:`（找到创建表单收集处，`payload.schedule = schedule` 保留）
- `mountYamlEditor(content, readOnly)` 的 readOnly 实参传 false 或移除参数

- [ ] **Step 2: style.css 删除内置徽章样式**

删除 `.badge.builtin`（约 1152 行）与 `.badge.custom`（约 1157 行）规则——后者随徽章分支移除一并成为死 CSS（如 `.badge` 基础类有其他用途则保留）。

- [ ] **Step 3: config-guide.md 更新**

- 删除任务 YAML 中 `id`/`name` 必填的说明，改为"任务 YAML 无需（也不建议）包含 id/name，显示名与调度在界面表单配置"
- `schedule` 块相关说明改为"YAML 禁止 schedule 块，调度在弹窗配置"

- [ ] **Step 4: api.js / yaml-editor.js 检查**

`grep -n "builtin\|readOnly" dg-web/frontend/src/js/core/api.js`，清理引用（api.js 若只是透传 JSON 则无需改动）。注意：`yaml-editor.js` 内含 CodeMirror 第三方打包代码，其内部大量 `readOnly` 命中为编辑器库自有逻辑，**不要**在该文件里按关键词清理，仅核对自写包装层是否传了 `readOnly` 语义给编辑器（可保留，编辑器只读与任务只读无关）。

- [ ] **Step 5: 更新仓库根 README.md**

清理其中对"内置/自定义任务区分"的过时描述（`grep -n "builtin\|内置" README.md` 定位），改为：任务由 Web 控制台创建，元数据存 SQLite `tasks` 主表，YAML 只含生成配置。

- [ ] **Step 6: 打包验证前端资源映射**

Run: `mvn package -pl dg-web -am -DskipTests`
Expected: BUILD SUCCESS（frontend/src 资源映射进 jar static/）

- [ ] **Step 7: Commit（需用户授权）**

```bash
git add dg-web/frontend/ README.md
git commit -m "feat(web): 前端移除内置任务徽章与只读态，请求字段更名 fileName"
```

---

## Task 9: dg-ai 适配

**Files:**
- Modify: `dg-ai/src/main/java/com/datagenerator/ai/prompt/SystemPrompt.java`
- Check: `dg-ai/src/main/java/com/datagenerator/ai/client/DgWebClient.java`、`dg-ai/src/main/java/com/datagenerator/ai/tool/ConfigTools.java`、`ConfigDraftManager.java`

- [ ] **Step 1: SystemPrompt 更新**

第 21、26、33 行附近：

- 第 21 行改为：任务配置 YAML 包含：`writer 或 writers / tables[] / seeds[]（可选）/ constraints[]（可选）`；**不包含 `id`、`name`、`schedule`**——任务 id 由系统生成，显示名通过请求字段提交，调度通过调度接口配置
- 第 26 行删除（"`id` 与 `name` 均为必填"）
- 第 33 行保留"YAML 禁止 schedule 块"并更新措辞
- 第 48 行 `startConfigDraft(draftId, headerYaml)` 的"传入 id/name/writer"说明改为"传入 writer 等顶层配置，不含 id/name"

- [ ] **Step 2: 显式修改 ConfigTools 中指导模型写 id/name 的措辞**

已核实以下位置，全部改为"YAML 不写 id/name（系统生成，显示名通过请求字段提交，调度通过调度接口配置）"：

- `ConfigTools.startConfigDraft`/`startEditDraft` 的工具描述（约 177-180 行）：删除"header YAML 传 id/name"的指导
- `ConfigTools` 的 `ID_PATTERN`/`NAME_PATTERN`（约 36-38 行）：如仅用于从 YAML 提取 id/name，删除并清理使用处（web 层 `stripMetaFields` 已兜底剥离，但 AI 侧不更新会持续生成带 id/name 的 YAML）

- [ ] **Step 3: 检查 ConfigDraftManager / DgWebClient**

`grep -n "id\|name" dg-ai/src/main/java/com/datagenerator/ai/tool/ConfigDraftManager.java dg-ai/src/main/java/com/datagenerator/ai/client/DgWebClient.java`：

- `DgWebClient.createConfig(displayName, yaml)` 请求体为 `displayName` + `content`（第 109-126 行），**无需改动**（文件名回退为生成的 id 由 dg-web 负责）
- `ConfigDetail/ConfigSummary` 解析 `id/name/fileName` 字段（响应侧字段保留），**无需改动**
- 如有测试 fixture 断言 YAML 含 id/name，同步更新

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -pl dg-ai`
Expected: 全绿

- [ ] **Step 5: Commit（需用户授权）**

```bash
git add dg-ai/
git commit -m "docs(ai): SystemPrompt 与 ConfigTools 更新任务 YAML 格式说明，id/name 不再写入"
```

---

## Task 10: 全量回归与打包

- [ ] **Step 1: 全量测试**

Run: `mvn clean test`
Expected: 全部模块 BUILD SUCCESS、所有测试通过

- [ ] **Step 2: 打包 Web**

Run: `mvn clean package -pl dg-web -am -DskipTests`
Expected: BUILD SUCCESS，产出 `dg-web/target/dg-web-0.1.0-SNAPSHOT.jar`

- [ ] **Step 3: 手动验证清单（启动 `java -jar dg-web/target/dg-web-0.1.0-SNAPSHOT.jar`）**

1. 启动日志无异常；SQLite 中 `tasks` 表已建、`task_schedules` 已删除（可用 sqlite3 或日志确认）
2. Web 控制台任务列表为空（无内置任务），无"内置"徽章
3. 新建任务：YAML 只写 tables/writers（不含 id/name）→ 创建成功，列表显示 displayName，创建时间正确
4. 新建任务时 YAML 含 schedule 块 → 报错提示
5. 新建任务时请求携带 schedule → 调度生效（列表显示 cron 与下次运行时间）
6. 编辑任务：改 YAML 与显示名 → 保存成功
7. 删除任务：文件与表行同时消失；手动删掉文件后再删除任务 → 仍可删除（僵尸行清理）
8. 运行任务 → 运行记录正常、日志显示任务显示名
9. 定时调度到点触发运行（cron 设为每分钟验证）
10. AI 对话创建任务（如有 dg-ai 环境）→ 创建成功且 YAML 无 id/name

- [ ] **Step 4: 收尾 Commit（需用户授权）**

```bash
git add docs/superpowers/
git commit -m "docs: 新增移除内置任务设计规格与实现计划"
```

---

## 风险与备注

1. **TaskRunService 文件读取路径**：保留 `ConfigPathResolver.open` 多来源读取。classpath/主目录已无 task-configs 资源，行为等效单一来源；若评审认为应显式单一来源，在 Task 6 补充直接读 overlay 文件的实现。
2. **`task_runs.config_path` 兼容**：老运行记录的 `task-configs/xxx.yaml` 引用失效任务时，"查看配置/重跑"返回 404（`TaskConfigNotFoundException`）——与规格 §7 一致（不迁移决策的自然后果）。
3. **并发创建**：`generateUniqueTaskId` 的检查-插入非原子；单实例部署可接受（现状同为非原子）。
4. **dto 包内 `name` 语义**：请求 `fileName`、响应 `name`（=display_name），前端与 dg-ai 已按本计划对齐，新调用方注意区分。
