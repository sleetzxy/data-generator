package com.datagenerator.ai.agent.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.tool.model.DgWebModels.PreviewResult;
import com.datagenerator.ai.tool.model.DgWebModels.PreviewTable;
import org.junit.jupiter.api.Test;

class JobArtifactSummaryFormatterTest {

    private final JobResultProcessor processor = new JobResultProcessor(null);

    @Test
    void summarizeReferenceJob_includesFileNameAndTableCount() {
        String yaml = "writer:\n  type: csv\ntables:\n  - name: t1\n  - name: t2";

        String summary = processor.summarizeReferenceJob("demo.yaml", yaml);

        assertThat(summary).contains("dg-ref:demo.yaml");
        assertThat(summary).contains("2 张表");
        assertThat(summary).doesNotContain("type: csv");
    }

    @Test
    void summarizeDraftStored_incomplete_marksGenerating() {
        String yaml = "tables:\n  - name: t1";

        String summary = processor.summarizeDraftStored(yaml, true);

        assertThat(summary).contains("生成中");
    }

    @Test
    void summarizePreviewResult_containsStatusAndTableRows() {
        PreviewResult result = new PreviewResult(
                "OK", "1ms", java.util.List.of(new PreviewTable("t1", 3, java.util.List.of())));

        String summary = processor.summarizePreviewResult(result);

        assertThat(summary).contains("预览完成 status=OK");
        assertThat(summary).contains("t1(3行)");
    }

    @Test
    void summarizeTables_onlyCountsTableLevelNames() {
        String yaml = """
                tables:
                  - name: customers
                    columns:
                      - name: id
                  - name: orders
                    generators:
                      - name: seq_gen
                """;

        assertThat(JobResultProcessor.summarizeTables(yaml)).isEqualTo("，约 2 张表");
    }
}
