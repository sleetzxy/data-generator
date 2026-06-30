## Cursor 开发辅助（本 Skill 适用范围）

本 Skill 仅用于 **在 Cursor IDE 内协助你编写 / 评审 Job YAML**，不对接 dg-ai 运行时 Tool。

| 场景 | 做法 |
|------|------|
| 查已有 Job | Read / Grep `configs/jobs/`、`dg-web/src/main/resources/configs/jobs/` |
| 查连接名 | Read `application.yml` 或 `application-local.yml` 的 `data-generator.connections` |
| 查 Schema | Read `configs/schemas/` |
| 查规范 | **优先** Read `dg-web/src/main/resources/static/docs/config-guide.md`，必要时查 `reference.md` |
| 输出 YAML | 对话中以代码块给出；用户明确要求落盘时再用 Write 写入 configs |
| 校验 | 提示用户在 Web 控制台「校验」或本地跑相关测试；**不**假设可调用 `validateJobYaml` 等 REST Tool |

**与 dg-ai 的关系：** 线上对话 Agent（`job-generator`）的系统提示在 `dg-ai/src/main/resources/prompt/templates/job-generator/`。若调整工作流程并希望 FAB 助手行为一致，需另行同步该目录（与 Cursor Skill 独立）。
