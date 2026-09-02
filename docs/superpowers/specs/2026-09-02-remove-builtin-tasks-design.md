# 设计规格：移除内置任务与任务类型，任务元数据入 SQLite 主表

> 日期：2026-09-02
> 状态：已批准
> 相关代码：dg-web（TaskConfigService、TaskScheduleService、TaskScheduleManager、storage）、dg-core（YamlConfigLoader）、dg-ai（ConfigTools、SystemPrompt）、前端

## 背景与动机

当前系统存在"内置任务"与"自定义任务"的隐式区分：任务配置的来源位置（jar classpath / config-dir 主目录 = 内置，writable overlay = 自定义）决定了一系列行为差异——内置任务只读不可改删、调度从 YAML 读取且 API 只读、自定义任务调度存 SQLite 且 YAML 禁止内嵌 schedule、列表排序不同、createdAt 语义不同。这一区分由 `isBuiltin()`（`existsOnClasspath`）运行时推导，驱动 6 处行为差异，增加了心智负担与维护成本（调度存储双分支、只读异常、校验规则分裂）。

**决策：彻底移除内置任务概念与任务类型区分。** 所有任务统一由 Web 创建，任务元数据（id、文件名、显示名、调度配置、创建时间）存 SQLite `tasks` 主表，生成配置（tables/writers/connections/seeds/constraints）存 YAML 文件。

## 决策记录

| 决策点 | 选择 | 理由 |
|---|---|---|
| YAML 内容存储 | 文件（writable-config-dir），主表存元数据 + 路径 | 用户指定；保持 YAML 可读可导出 |
| YAML 中 id/name 字段 | 不再要求；写文件前剥离 id/name/schedule 字段 | 元数据唯一来源是主表，避免两份事实来源 |
| classpath 内置任务 YAML（29 个，其中顶层 6 个实际被列为内置任务） | 直接删除整个 configs 目录，jar 不再携带任务配置 | 用户指定 |
| 现有数据迁移 | 不迁移（方案 C），老任务与老调度记录丢弃，用户手工重建 | 用户指定；避免迁移逻辑复杂度 |
| dg-core TaskConfig 模型 | 不变，web 层运行时从主表注入 id/name | core 引擎仅将 id/name 用于错误消息，注入即可 |
| config-dir 配置项 | 保留不动 | classpath:configs 目录缺失或为空均无害；ConfigPathResolver 机制仍供 references/constraints 等引用文件使用 |
| task_schedules 表 | 启动时 DROP，调度字段并入 tasks 主表 | 调度配置是任务元数据的一部分 |

## 1. 领域模型

- **任务（Task）**：由 Web 创建的唯一实体。元数据存 SQLite `tasks` 主表；生成配置存 YAML 文件。YAML 不包含 `id`、`name`、`schedule` 字段。
- **内置/自定义概念删除**：不存在两种任务，`builtin`/`readOnly`/类型区分全部移除，相关行为差异（只读保护、排序差异、调度双分支、createdAt 特判）一并消除。

## 2. 存储结构

```sql
CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,                 -- 自动生成 taskXXXX
    file_name TEXT NOT NULL UNIQUE,      -- 文件名（不含扩展名），API 主键
    display_name TEXT NOT NULL,          -- 显示名称
    schedule_enabled INTEGER NOT NULL DEFAULT 0,
    schedule_cron TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks(created_at);
```

- 启动时执行 `DROP TABLE IF EXISTS task_schedules`（不迁移，老调度记录丢弃）
- 文件布局：`{writable-config-dir}/task-configs/{file_name}.yaml`，任务文件只从该目录读写，不再走 classpath/主目录多来源
- 现有 9 个老任务文件与 30 个内置文件均不进新表，成为孤儿文件（用户可手动删除）
- `task_runs` 表不动，`config_path` 格式保持 `task-configs/xxx.yaml`

## 3. API 与 DTO

| 端点 | 变化 |
|---|---|
| `GET /api/v1/task-configs` | 查 `tasks` 表分页（name 过滤、created_at 倒序），不再扫描目录；响应去掉 `skipped` |
| `GET /{fileName}` | 查表 + 读文件，文件缺失返回 404 |
| `POST` | 校验 YAML（YAML 含 `schedule` 块则拒绝并提示改用请求字段，与现状一致；`id`/`name` 字段静默剥离）→ 生成 id 与文件名（未显式指定 fileName 时，默认 file_name = 生成的 id，现状规则不变）→ 写文件 → 插表（displayName 必填，来自请求字段；请求体可选携带 `schedule`，原子写入 tasks 行字段并触发重排，与现状一致） |
| `PUT /{fileName}` | 更新 YAML 文件（校验规则同 POST）+ `display_name`/`updated_at`；请求体同样可选携带 `schedule`，写入 tasks 行字段并触发重排 |
| `DELETE /{fileName}` | 以表行为准：删表行 + 尽力删文件（文件已缺失时仍删行，避免僵尸行无法清除）+ 取消调度 |
| `GET/PUT /{fileName}/schedule` | 直接读写 `tasks` 表字段 |

DTO 变化：
- `TaskConfigRequest`：可选文件名字段 `name` 重命名为 `fileName`，与响应字段 `fileName` 对齐，消除请求/响应中 `name` 键语义不一致（请求=文件名、响应=显示名）；`displayName` 不变
- `TaskConfigResponse`：去掉 `builtin`/`readOnly`；`name` 即表里的 `display_name`；`createdAt` 来自表
- `TaskScheduleResponse`：去掉 `editable`（恒可编辑）
- 删除 `TaskConfigSkipInfo`、`ReadOnlyScheduleException`

## 4. 运行与调度链路

- **提交运行**：`validateConfigPath` 改为查 `tasks` 表存在性；执行前从表读取 display_name/id 注入内存中的 `TaskConfig`（core 模型不变）
- **调度**：`TaskScheduleManager.reloadAll()` 查表（enabled=1 且有 cron）注册触发器，不再依赖 `isBuiltin()`；`TaskScheduleService` 删除双分支，读写统一走 `tasks` 表；删除 `TaskScheduleRepository`
- **dg-core 适配**：`YamlConfigLoader` 放宽 `name`/`id` 必填校验（可解析但允许缺失）

## 5. 前端与 dg-ai 适配

- 前端 `tasks.js`/`api.js`/`style.css`：移除内置徽章、只读态、编辑/删除禁用逻辑；创建/编辑请求字段 `name` → `fileName` 同步修改；`yaml-editor.js` 与 `config-guide.md` 更新说明（YAML 无需 name/id）
- dg-ai：`DgWebClient` 适配响应字段；`ConfigTools`/`ConfigDraftManager` 生成 YAML 时不再写 id/name（如写则由 web 层剥离兜底）；`SystemPrompt` 中任务 YAML 格式说明同步更新

## 6. 配置与删除清单

- 删除 `dg-web/src/main/resources/configs/` 整个目录（29 个内置 YAML，其中顶层 6 个实际被列为内置任务）
- `config-dir` 配置项保留不动；`ConfigPathResolver` 多来源机制保留（供引用文件使用），仅任务文件读写不再经过它
- 删除类/资源：`TaskScheduleRepository`、`ReadOnlyScheduleException`、`TaskConfigSkipInfo`
- `SqliteSchemaInitializer`：建 `tasks` 表 + 索引 + `DROP TABLE IF EXISTS task_schedules`，移除 task_schedules 建表语句与 `ensureColumn` 调用

## 7. 错误处理与测试

- 表行存在但文件缺失 → 404 并提示配置文件缺失
- 历史运行记录（task_runs 不动）中引用已删除任务文件的条目，查看/重跑将 404——这是"不迁移、丢弃"决策的自然后果，不做额外处理
- YAML 校验失败 → 400（现有逻辑）
- 创建时写文件成功但插表失败 → 回滚删文件（沿用现有 rollback 模式）
- 测试：`TaskConfigServiceTest`/`TaskScheduleServiceTest`/`TaskScheduleManagerTest`/`TaskRunServiceTest` 重写适配；`YamlConfigLoaderTest` 补"name/id 可缺失"用例；SQLite 集成测试验证新表结构

## 影响范围汇总

| 模块 | 文件 | 动作 |
|---|---|---|
| dg-web storage | SqliteSchemaInitializer、TaskScheduleRepository | 改表结构；删除仓库类 |
| dg-web service | TaskConfigService、TaskScheduleService、TaskScheduleManager、TaskRunService | 改为表驱动 CRUD；删 builtin 分支 |
| dg-web dto | TaskConfigRequest、TaskConfigResponse、TaskScheduleResponse、TaskConfigSkipInfo | 重命名请求字段/删字段/删类 |
| dg-web controller | TaskConfigController | 适配 |
| dg-web exception | ReadOnlyScheduleException | 删除 |
| dg-core | YamlConfigLoader | 放宽 name/id 必填 |
| dg-web resources | configs/ 目录 | 删除 |
| dg-web frontend | tasks.js、api.js、style.css、yaml-editor.js、config-guide.md | 去徽章/只读态 |
| dg-ai | DgWebClient、ConfigTools、SystemPrompt | 适配字段与 YAML 格式说明 |
