# 任务运行日志 SQLite 持久化设计规格

**日期：** 2026-06-05  
**状态：** 已批准  
**版本：** 1.0

---

## 1. 概述

### 1.1 目标

将 Data Generator 的任务运行日志与任务记录从内存存储迁移至 SQLite 数据库，实现应用重启后历史任务与日志仍可查询，Web UI「运行记录」功能可持续可用。

### 1.2 背景

当前实现：

- `JobLogStore` 使用 `ConcurrentHashMap` 内存存储，每任务最多 500 条日志
- `JobService.jobs` 使用 `ConcurrentHashMap` 内存存储任务元数据
- 应用重启后所有任务记录与日志丢失

### 1.3 已确认决策

| 决策项 | 选择 |
|--------|------|
| 持久化范围 | 日志 + 任务记录（status、jobConfig、progress、details 等） |
| 保留策略 | 永久保留，不自动清理 |
| 重启处理 | RUNNING / PENDING 任务标记为 CANCELLED，并写入 WARN 日志 |
| 日志上限 | 取消 500 条限制，全量保存 |
| 实现方案 | Spring JDBC + 原生 SQL（方案 A） |

### 1.4 非目标

- 不持久化 Preview 任务的内存行数据（`JobResponse.rows`）
- 不实现任务断点续跑
- 不引入 JPA / Flyway / Liquibase
- 不改动 REST API 路径与响应结构
- 不改动 Web UI

---

## 2. 架构

### 2.1 方案选择

采用 **Spring JDBC + sqlite-jdbc**，手写 Repository 层。理由：

- 依赖最少，与项目现有 JDBC 插件风格一致
- 仅两张表，无需 ORM
- 避免 JPA + SQLite 方言与并发问题

未采用 JPA（过重）、内存+DB 双写（一致性复杂）。

### 2.2 组件结构

```
dg-web/
  com.datagenerator.web.storage/
    StorageProperties          # sqlite-path 配置
    SqliteDataSourceConfig     # DataSource Bean + 建表 + WAL
    JobRepository              # jobs 表 CRUD
    JobLogRepository           # job_logs 表 append/query/delete
    JobStartupRecovery         # 启动时将遗留任务标记 CANCELLED

  com.datagenerator.web.service/
    JobLogStore                # 委托 JobLogRepository，对外接口不变
    JobService                 # ConcurrentHashMap → JobRepository
    AsyncJobExecutor           # 注入 JobRepository 替代共享 Map
```

### 2.3 依赖

在 `dg-web/pom.xml` 新增：

- `spring-boot-starter-jdbc`
- `org.xerial:sqlite-jdbc`

---

## 3. 数据模型

### 3.1 表 `jobs`

| 列 | 类型 | 说明 |
|----|------|------|
| job_id | TEXT PRIMARY KEY | UUID |
| status | TEXT NOT NULL | PENDING / RUNNING / COMPLETED / FAILED / CANCELLED |
| job_config | TEXT | 配置文件路径 |
| submitted_at | TEXT NOT NULL | ISO-8601 |
| duration | TEXT | 如 "12.3s"，可空 |
| error_message | TEXT | 可空 |
| total_tables | INTEGER NOT NULL DEFAULT 0 | progress |
| completed_tables | INTEGER NOT NULL DEFAULT 0 | progress |
| total_rows | INTEGER NOT NULL DEFAULT 0 | progress |
| written_rows | INTEGER NOT NULL DEFAULT 0 | progress |
| failed_rows | INTEGER NOT NULL DEFAULT 0 | progress |
| details_json | TEXT | `List<TableDetail>` 序列化为 JSON，可空 |

索引：`CREATE INDEX idx_jobs_submitted_at ON jobs(submitted_at DESC)`

### 3.2 表 `job_logs`

| 列 | 类型 | 说明 |
|----|------|------|
| id | INTEGER PRIMARY KEY AUTOINCREMENT | 保证写入顺序 |
| job_id | TEXT NOT NULL | 外键逻辑关联 jobs.job_id |
| logged_at | TEXT NOT NULL | ISO-8601 |
| level | TEXT NOT NULL | INFO / WARN / ERROR |
| message | TEXT NOT NULL | 日志内容 |

索引：`CREATE INDEX idx_job_logs_job_id ON job_logs(job_id, id)`

### 3.3 建表时机

应用启动时由 `SqliteDataSourceConfig` 执行 `CREATE TABLE IF NOT EXISTS`，并设置 `PRAGMA journal_mode=WAL`。

---

## 4. 配置

```yaml
data-generator:
  storage:
    sqlite-path: ./data/dg-jobs.db   # 默认值
```

- 路径可配置，默认与 `writable-config-dir`（`./data/configs`）同级目录
- 启动时自动创建父目录（若不存在）
- 通过 `StorageProperties` + `DataGeneratorProperties` 绑定

---

## 5. 行为规格

### 5.1 任务生命周期

| 操作 | 数据库行为 |
|------|-----------|
| submit | INSERT jobs (PENDING)；INSERT log |
| 开始执行 | UPDATE jobs SET status=RUNNING |
| 执行中 | INSERT logs（表级进度、完成信息等） |
| 完成 | UPDATE jobs SET status=COMPLETED + progress + details_json + duration |
| 失败 | UPDATE jobs SET status=FAILED + error_message |
| cancel | UPDATE jobs SET status=CANCELLED；INSERT log |
| remove | DELETE job_logs + DELETE jobs（仅终态，规则不变） |

**异步失败双路径说明：** `executeAndStore` catch 块写 FAILED 后 rethrow，`AsyncJobExecutor` catch 块也会写 FAILED。迁移后以 `AsyncJobExecutor` 的 catch 为最终权威；`executeAndStore` 负责持久化 FAILED 状态与 error_message，`AsyncJobExecutor` 仅补充日志，避免重复 UPDATE。

**`AsyncJobExecutor` 装配变更：** 当前由 `JobService` 构造函数内 `new AsyncJobExecutor(...)` 并共享 `ConcurrentHashMap`。改为 `@Component`，构造函数注入 `JobRepository` + `JobLogStore`，`JobService` 改为注入 `AsyncJobExecutor`。

### 5.2 启动恢复

监听 `ApplicationReadyEvent`，执行一次：

1. `SELECT job_id FROM jobs WHERE status IN ('PENDING', 'RUNNING')`
2. 批量 `UPDATE jobs SET status='CANCELLED'`
3. 为每个受影响 job_id INSERT WARN 日志：`服务重启，任务已取消`

### 5.3 日志写入容错

日志 INSERT 失败时记录 SLF4J ERROR，**不阻断**任务主流程。

### 5.4 API 兼容性

以下端点行为保持不变：

| 端点 | 说明 |
|------|------|
| GET /api/v1/jobs | 从 DB 查询，按 submitted_at 降序 |
| GET /api/v1/jobs/{id} | 从 DB 查询 |
| GET /api/v1/jobs/{id}/logs | 从 DB 全量返回，按 id 升序 |
| DELETE /api/v1/jobs/{id}/record | 删除 DB 记录及关联日志 |

---

## 6. 并发

| 场景 | 策略 |
|------|------|
| 多任务并行 | SQLite WAL 模式 |
| 同 jobId 写日志 | 单任务在单线程执行器内写入，无竞态 |
| 状态更新 | 每次变更直接 UPDATE，不再维护内存 Map |

---

## 7. 错误处理

| 场景 | 策略 |
|------|------|
| SQLite 文件目录不可写 | 启动失败，明确错误信息 |
| 建表失败 | 启动失败 |
| submit 时 jobs INSERT 失败 | 拒绝提交，返回 500 |
| 执行中 jobs UPDATE 失败 | 任务标记 FAILED，写入 error_message |
| 日志 INSERT 失败 | SLF4J ERROR，任务继续 |
| 查询不存在的 jobId | 404 JobNotFoundException（不变） |

---

## 8. 测试策略

| 测试类 | 覆盖 |
|--------|------|
| JobRepositoryTest | insert / update / findById / listAll / delete |
| JobLogRepositoryTest | append 顺序 / getLogs 全量 / 级联删除 |
| JobStartupRecoveryTest | 遗留 RUNNING → CANCELLED + WARN 日志 |
| JobServiceAsyncTest 等 | 改用 SQLite（`:memory:` 或临时文件） |
| JobControllerTest | Mock JobService，API 层无变化 |

测试使用 `:memory:` SQLite 或 `@TempDir` 临时文件，互不干扰。提供统一的测试 `@Configuration`（Test DataSource + Schema 初始化），供 `JobServiceAsyncTest`、`AsyncJobExecutorCancelTest` 等复用，避免各测试类重复建 DataSource。

---

## 9. 迁移

首次部署无需数据迁移：旧内存数据随重启自然丢弃，新任务开始写入 SQLite。

---

## 10. 实现清单

1. 添加 Maven 依赖（jdbc + sqlite-jdbc）
2. 新增 `StorageProperties` 与配置绑定
3. 新增 `SqliteDataSourceConfig`（DataSource + 建表）
4. 实现 `JobRepository`、`JobLogRepository`
5. 重构 `JobLogStore` 委托 Repository
6. 重构 `JobService`、`AsyncJobExecutor` 使用 `JobRepository`
7. 实现 `JobStartupRecovery`
8. 补充单元测试并更新现有测试
9. 验证 `mvn clean test` 通过
