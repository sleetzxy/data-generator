package com.datagenerator.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.memory.SummarizingChatMemory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

class JobGeneratorMemoryCompressorTest {

    private final JobGeneratorMemoryCompressor compressor = new JobGeneratorMemoryCompressor();

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

        String compressed = compressor.compressConversationText(text);

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

        String compressed = compressor.compressConversationText(text);

        assertThat(compressed).doesNotContain("结构化输出已压缩");
        assertThat(compressed).contains("仅说明、暂无 YAML");
    }

    @Test
    void compressConversationText_keepsLongExplanation() {
        String text = "这是说明。" + "x".repeat(5000);

        String compressed = compressor.compressConversationText(text);

        assertThat(compressed).hasSize(5000 + "这是说明。".length());
    }

    @Test
    void summarizeReferenceJob_includesFileNameAndSize() {
        String yaml = "writer:\n  type: csv\ntables:\n  - name: t1\n  - name: t2";

        String summary = compressor.summarizeReferenceJob("demo.yaml", yaml);

        assertThat(summary).contains("dg-ref:demo.yaml");
        assertThat(summary).contains("2 张表");
        assertThat(summary).doesNotContain("type: csv");
    }

    @Test
    void summarizingChatMemory_storesCompressedAiMessage() {
        SummarizingChatMemory memory = new SummarizingChatMemory(
                "s1",
                MessageWindowChatMemory.builder().id("s1").maxMessages(10).build(),
                32_768,
                compressor);

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

        String result = compressor.compressToolResult(
                "validateDraftJobYaml", large, 1000);

        assertThat(result).isSameAs(large);
    }

    @Test
    void compressToolResult_compressesHugePreviewOnly() {
        String large = "x".repeat(50_000);

        String result = compressor.compressToolResult(
                "previewJobYaml", large, 32_768);

        assertThat(result).contains("previewJobYaml");
        assertThat(result).contains("预览明细未写入对话记忆");
        assertThat(result).doesNotContain("请缩小查询范围");
    }

    @Test
    void summarizeTables_onlyCountsTableLevelNames() {
        String yaml = """
                tables:
                  - name: customers
                    columns:
                      - name: id
                        type: BIGINT
                      - name: email
                        type: VARCHAR
                    generators:
                      - name: seq_gen
                        type: sequence
                  - name: orders
                    columns:
                      - name: order_id
                        type: BIGINT
                      - name: amount
                        type: DECIMAL
                """;

        String result = JobGeneratorMemoryCompressor.summarizeTables(yaml);

        assertThat(result).isEqualTo("，约 2 张表");
    }

    @Test
    void summarizeTables_ignoresDeeplyNestedNames() {
        String yaml = """
                tables:
                  - name: products
                    schema:
                      table: products
                      fields:
                        - name: sku
                          type: VARCHAR
                        - name: price
                          type: DECIMAL
                        - name: stock
                          type: INT
                """;

        String result = JobGeneratorMemoryCompressor.summarizeTables(yaml);

        assertThat(result).isEqualTo("，约 1 张表");
    }

    @Test
    void summarizeTables_returnsEmptyForNoTables() {
        String yaml = "writer:\n  type: csv";

        String result = JobGeneratorMemoryCompressor.summarizeTables(yaml);

        assertThat(result).isEmpty();
    }

    @Test
    void summarizeTables_returnsEmptyForNullYaml() {
        assertThat(JobGeneratorMemoryCompressor.summarizeTables(null)).isEmpty();
    }

    @Test
    void summarizeTables_returnsEmptyForBlankYaml() {
        assertThat(JobGeneratorMemoryCompressor.summarizeTables("  \n ")).isEmpty();
    }

    @Test
    void compressConversationText_nullReturnsEmpty() {
        String result = compressor.compressConversationText(null);
        assertThat(result).isEmpty();
    }

    @Test
    void compressConversationText_blankReturnsSame() {
        String result = compressor.compressConversationText("   ");
        assertThat(result).isEqualTo("   ");
    }

    @Test
    void compressConversationText_handlesUppercaseYamlFence() {
        String text = """
                ```YAML
                writer:
                  type: csv
                tables:
                  - name: t1
                ```
                """;

        String result = compressor.compressConversationText(text);

        assertThat(result).contains("dg-draft:");
        assertThat(result).doesNotContain("YAML");
        assertThat(result).doesNotContain("writer:");
    }

    @Test
    void compactExisting_compactsWithoutException() {
        SummarizingChatMemory memory = new SummarizingChatMemory(
                "s1",
                MessageWindowChatMemory.builder().id("s1").maxMessages(10).build(),
                32_768,
                compressor);

        memory.add(AiMessage.from("safe message"));
        // 压缩不会因正常输入而异常
        memory.compactExisting();
        assertThat(memory.messages()).hasSize(1);
    }
}
