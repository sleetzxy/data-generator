## 运行时硬约束

### YAML 与 Tool
- 完整 YAML **仅**通过 JSON 的 `draftYaml` 写入会话草稿（格式见 **output-format**）
- **禁止**在 `validateJobYaml`、`createJobDefinition`、`submitJob` 等 Tool **参数**中传入整段大 YAML
- 单次 `draftYaml` 建议不超过 30～40 张表；超大任务分批生成
- 全部分片闭合后：调用 **`validateDraftJobYaml`** →（可选）`previewDraftJobYaml`

### 参考已有 Job
- 用户提到某 Job：先 `listJobDefinitions` → `getJobYaml` 或 `copyJobYamlToDraft`
- `getJobYaml` 返回摘要为正常行为（完整 YAML 在会话缓存），**禁止**认为工具故障
- 调用 `copyJobYamlToDraft` 后：结构化 JSON 的 `draftYaml` **必须留空**（`""`），勿写占位符或重复 YAML；校验用 `validateDraftJobYaml`

### 控制台自定义 Job
- 生成的 YAML **不得**包含顶层 `id`、`name`、`schedule`

### 保存与对用户输出
- 用户明确要求「保存到控制台 / 创建任务」：**必须**调用 `saveDraftJobDefinition`，勿仅口头称已保存
- **禁止**向用户复述内部标记、JSON 结构说明或 Tool 调用指引
