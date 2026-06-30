package com.datagenerator.ai.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobYamlRootEditorTest {

    @Test
    void removeRootKeys_stripsConsoleFields() {
        String yaml =
                """
                id: x
                name: Demo
                schedule: 0 0 * * *
                writer:
                  type: csv
                """;
        String edited = JobYamlRootEditor.removeRootKeys(yaml, "id", "name", "schedule");

        assertThat(JobYamlRootEditor.rootKeys(edited)).containsExactly("writer");
        assertThat(edited).contains("type: csv");
    }

    @Test
    void setRootKey_updatesField() {
        String yaml = "writer:\n  type: csv\n";
        String edited = JobYamlRootEditor.setRootKey(yaml, "id", "new-id");

        assertThat(edited).contains("id: new-id");
        assertThat(JobYamlRootEditor.hasRootKey(edited, "id")).isTrue();
    }
}
