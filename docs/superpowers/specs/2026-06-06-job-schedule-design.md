# Job 定时调度设计规格

**日期：** 2026-06-06  
**状态：** 待审阅  
**版本：** 1.0

---

## 1. 概述

### 1.1 目标

为 Data Generator 的 Job 定义增加 **Cron 定时调度**能力：每个 Job 可独立配置是否启用调度；启用调度的 Job 仍可通过现有方式 **手动运行**；同一 Job 的定时触发与手动触发 **共用排队机制**，保证同一时刻仅有一个运行实例。

### 1.2 背景

当前实现：

- Job 定义以 YAML 存放于 `configs/jobs/`（内置 classpath + 自定义 overlay 文件）
- Job 执行通过 `POST /api/v1/jobs` 手动提交，每次产生独立 `jobId` 与 SQLite 运行记录
- Web 控制台任务列表已有「运行」按钮（`runDefinition(path)`）
- **无** Cron / `@Scheduled` / 调度元数据

### 1.3 已确认决策

| 决策项 | 选择 |
|--------|------|
| 调度表达式 | 仅 Cron（Spring 6 字段格式，如 `0 0 2 * * ?`） |
| 时区 | 服务器本地时区（JVM 默认） |
| 配置存储 | **内置 Job** → YAML `schedule` 块；**自定义 Job** → SQLite `job_schedules` 表 |
| 重叠策略 | **排队等待**：同一 `config_path` 同时只跑一个实例，后续触发 FIFO 入队 |
| 实现方案 | Spring `TaskScheduler` + 动态 `CronTrigger`（方案一） |
| API 路径参数 | 沿用现有约定：`{fileName}` = YAML 文件名（无扩展名），**非** YAML `id` 字段 |

### 1.4 非目标

- 多实例部署 / 分布式锁
- 每条 Job 独立时区配置
- 固定间隔（interval）调度
- 排队持久化与 misfire 补跑（停机期间错过的 Cron 不补跑）
- 内置 Job 在 Web UI 修改 schedule（仍通过改 YAML）
- Quartz 等重量级调度框架
- 修改 Job 定义 CRUD 的路径结构

---

## 2. 架构

### 2.1 方案选择

采用 **Spring `@EnableScheduling` + `ThreadPoolTaskScheduler` + 动态 `CronTrigger`**。

理由：

- 无新依赖，与 Spring Boot 3.3 一致
- Cron 解析与 `nextRunAt` 计算由 Spring `CronExpression` 提供
- 配置变更时可 cancel/reschedule，精度高

未采用 Quartz（单实例场景过重）、分钟轮询（精度差且仍需自研排队）。

### 2.2 组件结构

```
dg-web/
  com.datagenerator.web.config/
    SchedulingConfig              # @EnableScheduling + ThreadPoolTaskScheduler Bean

  com.datagenerator.web.storage/
    JobScheduleRepository         # job_schedules 表 CRUD
    SqliteSchemaInitializer       # 扩展建表 + jobs.trigger_source 列

  com.datagenerator.web.service/
    JobScheduleService            # 合并 YAML/DB 配置、校验、nextRunAt
    JobScheduleManager            # 启动/变更时注册或取消 CronTrigger
    JobScheduleExecutor           # 按 config_path 排队 + 调用 JobService
    JobService                    # submit 增加 trigger_source；手动运行走 Executor

  com.datagenerator.web.controller/
    JobDefinitionController       # GET/PUT /{fileName}/schedule

  com.datagenerator.web.dto/
    JobScheduleRequest
    JobScheduleResponse

  static/
    app.js / index.html           # 调度列 + 自定义 Job 调度设置弹窗
```

### 2.3 数据流

```
内置 YAML schedule ──┐
                     ├──> JobScheduleService ──> JobScheduleManager ──> CronTrigger
自定义 SQLite ──────┘                              │
                                                     │ 到点
                                                     v
                                            JobScheduleExecutor.enqueue(SCHEDULED)
                                                     │
手动 POST /jobs ─────────────────────────────────────┤ enqueue(MANUAL)
                                                     v
                                            JobService.submit(trigger_source)
                                                     v
                                            AsyncJobExecutor / 同步执行
                                                     │
                                            onComplete → dequeue 下一项
```

---

## 3. 标识符说明

项目中存在三个易混淆字段，API 与调度层用法如下：

| 字段 | 来源 | 示例 | 用途 |
|------|------|------|------|
| **fileName** | YAML 文件名（无扩展名） | `my_job` | REST 路径参数 `{fileName}` |
| **id** | YAML `id:` 字段 | `prod_daily_sync` | 业务唯一标识 |
| **name** | YAML `name:` 字段 | `生产日同步` | 展示名称 |
| **config_path** | 配置相对路径 | `jobs/my_job.yaml` | 排队键、SQLite 主键、`JobResponse.jobConfig` |

**约定：** 调度端点 `/api/v1/job-definitions/{fileName}/schedule` 中的 `{fileName}` 与现有 `GET/PUT/DELETE /api/v1/job-definitions/{fileName}` 一致，指配置文件名，**不是** YAML `id`。`fileName` 与 `id` 可以不同。

---

## 4. 数据模型

### 4.1 内置 Job — YAML `schedule` 块

```yaml
id: city_acd_wf_jq_preview
name: 城市交通事故预览造数
schedule:
  enabled: true
  cron: "0 0 2 * * ?"
tables:
  # ...
```

- `schedule` 可选；缺省或未写 `enabled: true` → 不注册 Cron
- `enabled: false` → 不注册 Cron，仍可手动运行
- 内置 Job 的 schedule **只读**（Web/API 不可修改，与 `readOnly` 一致）

**自定义 Job 的 YAML 不含 `schedule` 块**，调度配置仅存 SQLite，避免双写。

### 4.2 SQLite 表 `job_schedules`

| 列 | 类型 | 说明 |
|----|------|------|
| config_path | TEXT PRIMARY KEY | 如 `jobs/my_job.yaml` |
| enabled | INTEGER NOT NULL DEFAULT 0 | 0/1 |
| cron | TEXT | Cron 表达式，enabled=1 时必填 |
| updated_at | TEXT NOT NULL | ISO-8601 |

索引：主键即索引，无需额外索引。

### 4.3 `jobs` 表扩展

新增列：

| 列 | 类型 | 说明 |
|----|------|------|
| trigger_source | TEXT | `MANUAL` / `SCHEDULED`，可 NULL（历史数据） |

建表迁移：在 `SqliteSchemaInitializer` 中使用「检查列是否存在 → ALTER TABLE ADD COLUMN」模式（SQLite 无 IF NOT EXISTS for column）。

### 4.4 统一视图 `JobScheduleInfo`

Service 层合并 YAML 与 DB 后的逻辑模型：

| 字段 | 说明 |
|------|------|
| configPath | `jobs/xxx.yaml` |
| enabled | 是否启用调度 |
| cron | Cron 表达式 |
| editable | 是否可在 UI/API 修改（自定义=true，内置=false） |
| nextRunAt | 下次触发时间 ISO-8601，仅 enabled 且 cron 合法时有值 |

---

## 5. API

### 5.1 扩展 Job 定义响应

`JobDefinitionResponse` 增加可选字段 `schedule`（`JobScheduleResponse` 或 null）。

列表与详情接口自动携带合并后的 schedule 信息。

### 5.2 调度专用端点

```
GET  /api/v1/job-definitions/{fileName}/schedule
PUT  /api/v1/job-definitions/{fileName}/schedule
```

**Request / Response 体：**

```json
{
  "enabled": true,
  "cron": "0 0 2 * * ?",
  "editable": true,
  "nextRunAt": "2026-06-07T02:00:00+08:00"
}
```

| 场景 | 行为 |
|------|------|
| 自定义 Job | PUT 校验后写入 `job_schedules`，调用 `JobScheduleManager.reschedule` |
| 内置 Job | GET 返回 YAML 中的 schedule；PUT 返回 **403**，提示内置任务只读 |
| `enabled=true` 且 cron 为空或非法 | **400** Bad Request |
| Job 定义不存在 | **404** |
| Cron 校验 | `CronExpression.isValid(cron)` |

### 5.3 手动运行

保留 `POST /api/v1/jobs`，请求体不变：

```json
{ "jobConfig": "jobs/my_job.yaml" }
```

内部改为 `JobScheduleExecutor.enqueue(configPath, MANUAL)`，与定时触发共用排队。响应行为与现有一致（同步 200 / 异步 202）。

**排队提示：** 若当前有实例在跑或队列非空，仍返回 **202/200** 表示已接受；实际执行顺序由队列决定。Web UI toast：`任务已加入队列，等待当前任务完成后执行`（仅在有活跃实例或队列非空时）。

### 5.4 删除 Job 定义

`DELETE /api/v1/job-definitions/{fileName}` 时额外：

1. `JobScheduleManager.cancel(configPath)`
2. `JobScheduleExecutor.clearQueue(configPath)`
3. `DELETE FROM job_schedules WHERE config_path = ?`

---

## 6. 调度执行逻辑

### 6.1 `JobScheduleManager`

- 监听 `ApplicationReadyEvent`（在 `JobStartupRecovery` 之后），加载全部 Job 定义
- 对每个 `enabled=true` 且 cron 合法的 Job：`schedule(Runnable, CronTrigger)`
- 维护 `Map<configPath, ScheduledFuture<?>>` 便于 cancel/reschedule
- Job 定义 CRUD 或 schedule PUT 后调用 `reschedule(configPath)`
- 启动时清理 `job_schedules` 中无对应 Job 定义的 orphan 行

### 6.2 `JobScheduleExecutor`

每个 `config_path` 一个 `JobScheduleQueue`（内存）：

```
enqueue(configPath, trigger):
  if 队列空 and 无 PENDING/RUNNING 实例（同 config_path）:
    立即 JobService.submit(jobConfig, trigger)
  else:
    FIFO 入队
    SLF4J INFO 记录排队

onJobTerminal(configPath):   # COMPLETED / FAILED / CANCELLED
  if 队列非空:
    dequeue → submit
```

- **定时与手动共用**同一队列
- 队列 **不持久化**；进程重启后丢失未执行项
- Cron misfire：**丢弃**，不补跑

### 6.3 活跃实例判定

查询 `jobs` 表：`job_config = ? AND status IN ('PENDING', 'RUNNING')`。

### 6.4 `JobService.submit` 扩展

新增参数或内部字段 `triggerSource`（`MANUAL` / `SCHEDULED`），写入 `jobs.trigger_source`。

定时触发的 submit 不经过 HTTP，直接 Service 调用。

---

## 7. Web UI

### 7.1 任务列表

新增列：**调度**、**Cron**。

| 类型 | 调度列 | Cron 列 |
|------|--------|---------|
| 未配置 / disabled | 「未启用」 | `-` |
| 内置 + enabled | 「已启用」badge | cron 文本 |
| 自定义 + enabled | 「已启用」badge | cron 文本 |

### 7.2 调度设置（仅自定义 Job）

「更多」菜单增加 **调度设置**：

- 开关：启用调度
- 输入：Cron 表达式（placeholder: `0 0 2 * * ?`）
- 只读展示：下次执行时间（`nextRunAt`）
- 保存 → `PUT /api/v1/job-definitions/{fileName}/schedule`

内置 Job 不显示「调度设置」入口；schedule 信息只读展示。

### 7.3 手动运行

「运行」按钮行为不变；若入队则 toast 提示排队。

---

## 8. 错误处理

| 场景 | 策略 |
|------|------|
| Cron 非法（保存时） | 400，不写入 |
| Cron 非法（启动加载 YAML） | SLF4J WARN，跳过注册，schedule 视为未启用 |
| 调度触发时 YAML 加载失败 | submit 失败路径：FAILED 运行记录 + ERROR 日志 |
| SQLite schedule 写入失败 | 500 |
| 应用重启 | 全量 reload schedule；排队项丢失；遗留 PENDING/RUNNING 仍由 `JobStartupRecovery` 标记 CANCELLED |
| orphan `job_schedules` 行 | 启动时 DELETE |

---

## 9. 并发与部署

| 场景 | 策略 |
|------|------|
| 单实例 | 本设计目标场景 |
| 多实例 | **不支持**；多副本会导致重复 Cron 触发，本阶段不处理 |
| TaskScheduler 线程池 | 独立小池（如 pool-size=2），与 `AsyncJobExecutor` 分离 |
| 同 config_path | `JobScheduleQueue` 内 synchronized 或 per-key 锁 |

---

## 10. 测试策略

| 测试类 | 覆盖 |
|--------|------|
| `JobScheduleServiceTest` | cron 校验、YAML/DB 合并、内置只读、nextRunAt |
| `JobScheduleExecutorTest` | 排队：运行中 enqueue → 第二个在第一个完成后执行 |
| `JobScheduleManagerTest` | enabled 变更 reschedule；启动注册 |
| `JobScheduleRepositoryTest` | CRUD、orphan 清理 |
| `JobDefinitionControllerTest` | GET/PUT schedule；内置 403 |
| `JobControllerTest` | 手动 submit 委托 Executor（Mock） |

测试命名遵循 `feature_scenario_expected`。

---

## 11. 实现清单

1. `SqliteSchemaInitializer`：新增 `job_schedules` 表、`jobs.trigger_source` 列
2. `JobScheduleRepository`
3. `JobScheduleService`（合并配置、校验）
4. `SchedulingConfig` + `JobScheduleManager`
5. `JobScheduleExecutor` + 挂钩 `JobService` 完成回调
6. `JobService`：submit 支持 `triggerSource`；手动路径走 Executor
7. `JobDefinitionController`：schedule 端点；`JobDefinitionResponse` 扩展
8. 内置示例 YAML 可选添加 `schedule` 块（默认 disabled 或不添加）
9. Web UI：`index.html` + `app.js` 调度列与弹窗
10. 单元测试 + `mvn clean test`

---

## 12. 配置（可选）

本阶段不新增必填配置项。可选扩展：

```yaml
data-generator:
  schedule:
    pool-size: 2    # TaskScheduler 线程池，默认 2
```

YAGNI：首版可硬编码 pool-size=2，后续按需暴露。
