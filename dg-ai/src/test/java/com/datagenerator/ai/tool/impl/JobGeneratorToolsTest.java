package com.datagenerator.ai.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobDetail;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobSummary;
import com.datagenerator.ai.tool.impl.model.DgWebModels.PreviewResult;
import com.datagenerator.ai.tool.impl.model.DgWebModels.PreviewTable;
import com.datagenerator.ai.tool.impl.model.DgWebModels.ValidationResult;
import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import com.datagenerator.ai.tool.provider.JobGeneratorToolProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobGeneratorToolsTest {

    private AgentSessionRegistry sessionRegistry;
    private DataGeneratorWebClient webClient;
    private JobGeneratorTools tools;

    @BeforeEach
    void setUp() {
        sessionRegistry = new AgentSessionRegistry();
        webClient = mock(DataGeneratorWebClient.class);
        tools = new JobGeneratorTools(webClient, sessionRegistry);
    }

    @Test
    void validateDraftJobYaml_withDraft_delegatesToPort() {
        sessionRegistry.put(new AgentSession(
                "s1", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "deepseek", Instant.now()));
        sessionRegistry.find("s1").orElseThrow().setDraftYaml("writer:\n  type: csv");

        when(webClient.validateYaml("writer:\n  type: csv"))
                .thenReturn(new ValidationResult(true, List.of()));

        ValidationResult result = tools.validateDraftJobYaml("s1");

        assertThat(result.valid()).isTrue();
        verify(webClient).validateYaml("writer:\n  type: csv");
    }

    @Test
    void validateDraftJobYaml_noDraft_throws() {
        assertThatThrownBy(() -> tools.validateDraftJobYaml("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无 YAML 草稿");
    }

    @Test
    void previewDraftJobYaml_withDraft_returnsSummary() {
        sessionRegistry.put(new AgentSession(
                "s1", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "deepseek", Instant.now()));
        sessionRegistry.find("s1").orElseThrow().setDraftYaml("tables: []");

        PreviewResult previewResult = new PreviewResult("OK", "1ms", List.of(new PreviewTable("t1", 1, List.of())));
        when(webClient.preview("tables: []", 3, List.of("t1"))).thenReturn(previewResult);

        String result = tools.previewDraftJobYaml("s1", 3, List.of("t1"));

        assertThat(result).contains("预览完成");
        assertThat(result).contains("OK");
    }

    @Test
    void getJobYaml_cachesFullYamlAndReturnsSummary() {
        sessionRegistry.put(new AgentSession(
                "s1", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "deepseek", Instant.now()));
        String yaml = "writer:\n  type: csv\ntables:\n  - name: t1";
        when(webClient.findJob("demo.yaml"))
                .thenReturn(new JobDetail("jid", "Demo", "demo", yaml));

        String result = tools.getJobYaml("s1", "demo.yaml");

        assertThat(result).contains("dg-ref:demo");
        assertThat(result).doesNotContain("type: csv");
        assertThat(result).doesNotContain("validateDraftJobYaml");
        assertThat(sessionRegistry.find("s1").orElseThrow().getReferenceYamls())
                .containsEntry("demo", yaml);
    }

    @Test
    void getJobYaml_resolvesByJobId() {
        sessionRegistry.put(new AgentSession(
                "s1", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "deepseek", Instant.now()));
        String yaml = "writer:\n  type: csv";
        when(webClient.findJob("job-uuid")).thenReturn(null);
        when(webClient.listJobs())
                .thenReturn(List.of(new JobSummary("job-uuid", "City JQ", "city_jq")));
        when(webClient.findJob("city_jq"))
                .thenReturn(new JobDetail("job-uuid", "City JQ", "city_jq", yaml));

        String result = tools.getJobYaml("s1", "job-uuid");

        assertThat(result).contains("city_jq");
        verify(webClient).findJob("job-uuid");
        verify(webClient).findJob("city_jq");
    }

    @Test
    void copyJobYamlToDraft_stripsIdNameAndLoadsDraft() {
        sessionRegistry.put(new AgentSession(
                "s1", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "deepseek", Instant.now()));
        String yaml = "id: old-id\nname: Old Name\nschedule: 0 0 * * *\nwriter:\n  type: csv";
        when(webClient.findJob("city_jq"))
                .thenReturn(new JobDetail("old-id", "Old Name", "city_jq", yaml));
        when(webClient.validateYaml(anyString()))
                .thenReturn(new ValidationResult(true, List.of()));

        String result = tools.copyJobYamlToDraft("s1", "city_jq", "new-id", "New Name");

        assertThat(result).contains("dg-draft:");
        assertThat(result).doesNotContain("validateDraftJobYaml");
        AgentSession session = sessionRegistry.find("s1").orElseThrow();
        assertThat(session.getDraftYaml()).contains("type: csv");
        assertThat(session.getDraftYaml()).contains("id: new-id");
        assertThat(session.getDraftYaml()).contains("name: New Name");
        assertThat(session.getDraftYaml()).doesNotContain("schedule");
        assertThat(session.isDraftValidated()).isTrue();
        verify(webClient).validateYaml(anyString());
    }

    @Test
    void getJobYaml_missingSession_throws() {
        when(webClient.findJob("demo"))
                .thenReturn(new JobDetail("id", "Demo", "demo", "writer:\n  type: csv"));

        assertThatThrownBy(() -> tools.getJobYaml("missing", "demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("会话不存在");
    }
}
