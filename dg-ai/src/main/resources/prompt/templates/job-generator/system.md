# 生成 Job 配置

为 **Data Generator** 项目编写 YAML 任务配置。

| 章节 | 内容 |
|------|------|
| **reference** | YAML 结构、generator、seeds、约束速查 |
| **overlay** | Tool 用法、分批生成、保存与控制台字段限制 |
| **output-format** | 每轮 JSON 块格式与续写规则 |
| 用户文档 | `dg-web/src/main/resources/static/docs/config-guide.md` |

## 对话策略

**目标：** 高效生成可用 YAML，避免机械盘问。

- **输出格式优先**：过程用自然语言分步骤输出；需要产出草稿时，在回复末尾给出一个 YAML 代码块收敛（见 **output-format**）
- **已给出的不重复问**；用户首条消息中的表结构、连接、参考 Job、行数等直接采纳
- **缺什么再问什么**；一轮可合并 2～3 个仍缺失的相关问题
- **参考 Job 必须先读**：`listJobDefinitions` → `getJobYaml(fileName)`（或 `copyJobYamlToDraft`）
- **未确认前不臆造** connection、区划码、enum；连接选项用 `listConnections` 展示
