---
name: generate-job
description: 为 Data Generator 编写 YAML Job 配置。以 config-guide.md 与仓库内已有 Job 为准；用户要求创建/修改造数任务时使用。缺连接名、表结构等关键信息时再简短追问，禁止臆造。默认对话输出 YAML；用户明确要求落盘时才 Write。仅 Cursor IDE 开发辅助，不涉及 dg-ai REST Tool。
---

# 生成 Job 配置（Cursor 开发辅助）

协助在本仓库编写 **Data Generator** YAML 任务配置。本 Skill 不对接 dg-ai / Web FAB 运行时 Tool。

## 权威文档（优先阅读）

编写或评审 YAML 前，**先 Read 下列文档**，按指南结构与约定生成，不要凭记忆硬写：

| 优先级 | 文档 | 用途 |
|--------|------|------|
| 1 | `dg-web/src/main/resources/static/docs/config-guide.md` | **主参考**：Job 结构、连接、策略、约束、控制台 vs 内置文件差异 |
| 2 | [reference.md](reference.md) | 策略 / 约束速查（细节以 config-guide 为准） |
| 3 | `configs/jobs/`、`dg-web/src/main/resources/configs/jobs/` | 已有 Job 风格与写法 |
| 4 | `configs/schemas/` | 可复用的 Schema 片段 |
| 5 | `application.yml` / `application-local.yml` 的 `data-generator.connections` | 可用连接名（勿臆造） |

线上 Agent 提示词在 `dg-ai/src/main/resources/prompt/templates/job-generator/`，与本 Skill 独立维护。边界见 [overlay.md](overlay.md)。

## 工作方式

**原则：文档驱动 + 用户已给直接用 + 缺关键项才问。**

1. 根据用户意图 Read **config-guide** 相关章节（如 writer、seeds、策略、控制台任务规则）。
2. 若用户提到参考某 Job、表名或业务场景，Grep / Read 对应 YAML 对齐写法。
3. 用户消息里已明确的表结构、连接、行数、策略等**直接采用**，不重复追问。
4. 仅当无法从文档、配置或用户输入推断时，**一次性简短追问**缺失项（例如连接名、物理表名、seed SQL）；禁止列长清单、禁止分多轮盘问。
5. 输出 YAML 代码块；用户明确要求落盘时再用 Write。

**禁止臆造：** `connection` 名、JDBC、区划码、seed SQL、`enum.values` 等业务取值。无法确定时用占位说明或向用户确认，不要从其他 Job 照搬。

## 输出场景（摘自配置指南）

| 场景 | YAML 应包含 | 不应包含 |
|------|-------------|----------|
| **控制台自定义 Job** | `writer`/`writers`、`tables`、业务字段 | 顶层 `id`、`name`、`schedule` |
| **内置 Job 文件** | `id`、`name`、`writer`/`writers`、`tables` | — |
| **内置定时任务** | 上栏 + `schedule.enabled` + `schedule.cron` | 控制台自定义勿写 `schedule` |

落盘路径（仅用户要求 Write 时）：

- 内置：`dg-web/src/main/resources/configs/jobs/{id}.yaml`
- 自定义：`writable-config-dir/jobs/`（常见 `./data/configs/jobs/`）

## 交付与校验

- **默认：** 对话 Markdown 代码块输出 YAML，供复制；**不**主动 Write。
- **校验：** 提示用户在 Web 控制台「校验」或对照 config-guide 自检；不假设可调用 REST 校验接口。

## 常见错误（对照 config-guide）

- `writer` 与 `writers` 二选一；`reference.source` 须匹配上游 `tables[].name`
- 控制台自定义 YAML 勿写 `id` / `name` / `schedule`
- 身份证派生、`depends_on`、约束语法等以 config-guide 为准

## 禁忌

- 跳过 config-guide，凭印象写 YAML
- 多轮 / 清单式机械确认
- 臆造连接、区划、seed SQL
- 把本 Skill 当成 dg-ai Agent 去调 REST Tool
- 用户未要求就 Write 到 configs
