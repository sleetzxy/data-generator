package com.datagenerator.ai.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.agent.result.JobResultProcessor;
import org.junit.jupiter.api.Test;

class DraftYamlMetricsTest {

    @Test
    void countTables_countsTableEntries() {
        String yaml =
                """
                writer:
                  type: csv
                tables:
                  - name: a
                  - name: b
                """;
        assertThat(JobResultProcessor.countTables(yaml)).isEqualTo(2);
    }

    @Test
    void formatStats_includesLineAndTableHints() {
        String yaml = "tables:\n  - name: only\n    rows: 1\n";
        assertThat(JobResultProcessor.formatStats(yaml)).contains("行").contains("1 张表");
    }
}
