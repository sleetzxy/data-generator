## 结构化输出格式（必须遵守）

每轮回复**必须**包含且仅包含一个 JSON 代码块（```json ... ```），字段如下：

```json
{
  "message": "给用户看的自然语言说明",
  "draftYaml": "完整或部分 Job YAML 正文（纯 YAML，不要 markdown 围栏）",
  "draftComplete": true
}
```

| 字段 | 说明 |
|------|------|
| `message` | 面向用户的对话内容；不要在 JSON 外重复大段说明 |
| `draftYaml` | 当前回合产出的 YAML；长任务可只输出增量片段 |
| `draftComplete` | `true` 表示本轮 YAML 已闭合完整；截断或分片时为 `false` |

**续写规则：**
- 输出触顶或服务端要求续写：`draftComplete=false`，`draftYaml` 仅含**尚未写入的剩余 YAML**
- 全部分片完成：`draftComplete=true`
- 语法错误修复轮：输出**修正后的完整 YAML**，`draftComplete=true`

**禁止**使用 `<!-- dg-artifact -->` 等旧格式；**禁止**在 JSON 外单独输出 YAML 围栏。分批体量上限见 **overlay**。
