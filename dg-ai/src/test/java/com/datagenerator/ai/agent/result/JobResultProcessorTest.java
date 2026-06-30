package com.datagenerator.ai.agent.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.agent.runtime.JobSessionState;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.sse.SseEvent;
import com.datagenerator.ai.tool.model.DgWebModels.ValidationResult;
import com.datagenerator.ai.tool.web.DataGeneratorWebClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobResultProcessorTest {

    private DataGeneratorWebClient webClient;
    private JobResultProcessor processor;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        webClient = mock(DataGeneratorWebClient.class);
        processor = new JobResultProcessor(webClient);
        session = new AgentSession(
                "s1", "job-generator", "deepseek", Instant.now());
    }

    @Test
    void process_validYaml_setsDraftValidated() {
        String yaml = "writer:\n  type: csv\ntables:\n  - name: t1";
        String text = responseWithYaml("done", yaml);
        when(webClient.validateYaml(anyString()))
                .thenReturn(ValidationResult.ok());

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text), events::add);

        assertThat(needsContinue).isFalse();
        assertThat(JobSessionState.isDraftValidated(session)).isTrue();
        assertThat(JobSessionState.isDraftIncomplete(session)).isFalse();
        assertThat(JobSessionState.draftYaml(session)).isEqualTo(yaml);
        assertThat(events).isEmpty();
    }

    @Test
    void process_noYamlFence_keepsDraftUnchanged() {
        JobSessionState.setDraftYaml(session, "writer:\n  type: csv\ntables:\n  - name: old");
        JobSessionState.setDraftValidated(session, true);
        String text = "这轮先确认需求，暂不输出 YAML。";

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text), events::add);

        assertThat(needsContinue).isFalse();
        assertThat(JobSessionState.draftYaml(session)).contains("name: old");
        assertThat(JobSessionState.isDraftValidated(session)).isTrue();
        assertThat(events).isEmpty();
        verify(webClient, never()).validateYaml(anyString());
    }

    @Test
    void process_parseFailure_marksIncompleteAndEmitsValidationError() {
        String yaml = "writer:\n  type: csv\n  bad";
        String text = responseWithYaml("", yaml);
        when(webClient.validateYaml(anyString()))
                .thenReturn(ValidationResult.fail(
                        List.of("Failed to parse YAML content: bad yaml")));

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text), events::add);

        assertThat(needsContinue).isFalse();
        assertThat(JobSessionState.isDraftIncomplete(session)).isTrue();
        assertThat(JobSessionState.isDraftValidated(session)).isFalse();
        assertThat(events).anyMatch(event -> "validation_error".equals(event.getEvent()));
    }

    @Test
    void process_semanticValidationFailure_emitsValidationError() {
        String yaml = "writer:\n  type: csv";
        String text = responseWithYaml("", yaml);
        when(webClient.validateYaml(anyString()))
                .thenReturn(ValidationResult.fail(List.of("缺少 tables")));

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text), events::add);

        assertThat(needsContinue).isFalse();
        assertThat(JobSessionState.isDraftIncomplete(session)).isFalse();
        assertThat(JobSessionState.isDraftValidated(session)).isFalse();
        assertThat(events).anyMatch(event -> "validation_error".equals(event.getEvent()));
    }

    @Test
    void countTables_twoTables_returnsTwo() {
        String yaml = "tables:\n  - name: users\n    columns:\n  - name: orders\n";
        assertThat(JobResultProcessor.countTables(yaml)).isEqualTo(2);
    }

    @Test
    void formatStats_withTables_includesTableCount() {
        String yaml = "tables:\n  - name: users\n    columns:\n      - name: id";
        assertThat(JobResultProcessor.formatStats(yaml)).contains("行").contains("1 张表");
    }

    @Test
    void summarizeDraftStored_complete_generatesComment() {
        String yaml = "writer:\n  type: csv\ntables:\n  - name: t1\n    columns:\n      - name: id";
        String result = processor.summarizeDraftStored(yaml, false);
        assertThat(result).startsWith("<!-- dg-draft:").endsWith(" -->");
        assertThat(result).doesNotContain("生成中");
    }

    @Test
    void summarizeDraftStored_incomplete_includesGenerating() {
        String yaml = "tables:\n  - name: t1";
        String result = processor.summarizeDraftStored(yaml, true);
        assertThat(result).contains("生成中");
    }

    private static ChatResponse chatResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .build();
    }

    private static String responseWithYaml(String message, String draftYaml) {
        return """
                %s

                ```yaml
                %s
                ```
                """
                .formatted(message, draftYaml);
    }
}
