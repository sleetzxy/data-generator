package com.datagenerator.ai.memory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

class ChatMemoryContentCompressorTest {

    @Test
    void compressConversationText_replacesJsonBlockWithSummary() {
        String text = """
                说明
                ```json
                {
                  "message": "ok",
                  "draftYaml": "writer:\\n  type: csv\\ntables:\\n  - name: t1",
                  "draftComplete": true
                }
                ```
                """;

        String compressed = ChatMemoryContentCompressor.compressConversationText(text);

        assertThat(compressed).doesNotContain("type: csv");
        assertThat(compressed).contains("dg-draft:");
    }

    @Test
    void compressConversationText_emptyDraftYaml_keepsMessageNotVisiblePlaceholder() {
        String text = """
                ```json
                {"message":"仅说明、暂无 YAML","draftYaml":"","draftComplete":true}
                ```
                """;

        String compressed = ChatMemoryContentCompressor.compressConversationText(text);

        assertThat(compressed).doesNotContain("结构化输出已压缩");
        assertThat(compressed).contains("仅说明、暂无 YAML");
    }

    @Test
    void compressConversationText_keepsLongExplanation() {
        String text = "这是说明。" + "x".repeat(5000);

        String compressed = ChatMemoryContentCompressor.compressConversationText(text);

        assertThat(compressed).hasSize(5000 + "这是说明。".length());
    }

    @Test
    void summarizeReferenceJob_includesFileNameAndSize() {
        String yaml = "writer:\n  type: csv\ntables:\n  - name: t1\n  - name: t2";

        String summary = ChatMemoryContentCompressor.summarizeReferenceJob("demo.yaml", yaml);

        assertThat(summary).contains("dg-ref:demo.yaml");
        assertThat(summary).contains("2 张表");
        assertThat(summary).doesNotContain("type: csv");
    }

    @Test
    void summarizingChatMemory_storesCompressedAiMessage() {
        SummarizingChatMemory memory = new SummarizingChatMemory(
                "s1",
                MessageWindowChatMemory.builder().id("s1").maxMessages(10).build(),
                32_768);

        String text = """
                ```json
                {
                  "message": "",
                  "draftYaml": "writer:\\n  type: csv",
                  "draftComplete": true
                }
                ```
                """;
        memory.add(AiMessage.from(text));

        String stored = memory.messages().getLast().toString();
        assertThat(stored).contains("dg-draft:");
        assertThat(stored).doesNotContain("type: csv");
    }

    @Test
    void compressToolResult_neverCompressesValidateDraft() {
        String large = "x".repeat(50_000);

        String result = ChatMemoryContentCompressor.compressToolResult(
                "validateDraftJobYaml", large, 1000);

        assertThat(result).isSameAs(large);
    }

    @Test
    void compressToolResult_compressesHugePreviewOnly() {
        String large = "x".repeat(50_000);

        String result = ChatMemoryContentCompressor.compressToolResult(
                "previewJobYaml", large, 32_768);

        assertThat(result).contains("previewJobYaml");
        assertThat(result).doesNotContain("已省略");
    }
}
