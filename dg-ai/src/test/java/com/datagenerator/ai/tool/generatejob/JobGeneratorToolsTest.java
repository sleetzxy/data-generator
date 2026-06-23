package com.datagenerator.ai.tool.generatejob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.port.ConnectionCatalogPort;
import com.datagenerator.ai.port.JobDefinitionPort;
import com.datagenerator.ai.port.JobExecutionPort;
import com.datagenerator.ai.port.JobPreviewPort;
import com.datagenerator.ai.port.SchemaCatalogPort;
import com.datagenerator.ai.session.AgentSession;
import com.datagenerator.ai.session.AgentSessionRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobGeneratorToolsTest {

    private AgentSessionRegistry sessionRegistry;
    private JobDefinitionPort jobDefinitions;
    private JobPreviewPort preview;
    private JobGeneratorTools tools;

    @BeforeEach
    void setUp() {
        sessionRegistry = new AgentSessionRegistry();
        jobDefinitions = mock(JobDefinitionPort.class);
        preview = mock(JobPreviewPort.class);
        tools = new JobGeneratorTools(
                mock(ConnectionCatalogPort.class),
                jobDefinitions,
                mock(SchemaCatalogPort.class),
                preview,
                mock(JobExecutionPort.class),
                sessionRegistry);
    }

    @Test
    void validateDraftJobYaml_withDraft_delegatesToPort() {
        sessionRegistry.put(new AgentSession("s1", "generate-job", "deepseek", Instant.now()));
        sessionRegistry.find("s1").orElseThrow().setDraftYaml("writer:\n  type: csv");

        when(jobDefinitions.validateYaml("writer:\n  type: csv"))
                .thenReturn(new JobDefinitionPort.ValidationResult(true, List.of()));

        JobDefinitionPort.ValidationResult result = tools.validateDraftJobYaml("s1");

        assertThat(result.valid()).isTrue();
        verify(jobDefinitions).validateYaml("writer:\n  type: csv");
    }

    @Test
    void validateDraftJobYaml_noDraft_throws() {
        assertThatThrownBy(() -> tools.validateDraftJobYaml("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无 YAML 草稿");
    }

    @Test
    void previewDraftJobYaml_withDraft_delegatesToPort() {
        sessionRegistry.put(new AgentSession("s1", "generate-job", "deepseek", Instant.now()));
        sessionRegistry.find("s1").orElseThrow().setDraftYaml("tables: []");

        JobPreviewPort.PreviewResult expected =
                new JobPreviewPort.PreviewResult("OK", "1ms", List.of());
        when(preview.preview("tables: []", 3, List.of("t1"))).thenReturn(expected);

        JobPreviewPort.PreviewResult result = tools.previewDraftJobYaml("s1", 3, List.of("t1"));

        assertThat(result).isSameAs(expected);
    }
}
