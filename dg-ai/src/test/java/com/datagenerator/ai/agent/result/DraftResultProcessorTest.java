package com.datagenerator.ai.agent.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.TurnContinueMode;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.tool.impl.model.DgWebModels.ValidationResult;
import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import com.datagenerator.ai.tool.provider.JobGeneratorToolProvider;
import com.datagenerator.ai.web.dto.common.SseEvent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DraftResultProcessorTest {

    private DataGeneratorWebClient webClient;
    private DraftResultProcessor processor;
    private AgentSession session;
    private AiProperties.DraftContinueProperties settings;

    @BeforeEach
    void setUp() {
        webClient = mock(DataGeneratorWebClient.class);
        settings = new AiProperties.DraftContinueProperties();
        processor = new DraftResultProcessor(webClient, AgentIoLogger.disabled(), settings);
        session = new AgentSession(
                "s1", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "deepseek", Instant.now());
    }

    @Test
    void process_validYaml_setsDraftValidated() {
        String yaml = "writer:\n  type: csv";
        String text = jsonBlock("done", yaml, true);
        when(webClient.validateYaml(anyString()))
                .thenReturn(ValidationResult.ok());

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text, FinishReason.STOP), events::add);

        assertThat(needsContinue).isFalse();
        assertThat(session.isDraftValidated()).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    void process_incompleteJson_schedulesContinue() {
        String text = jsonBlock("", "writer:\n  type: csv", false);

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text, FinishReason.STOP), events::add);

        assertThat(needsContinue).isTrue();
        assertThat(session.getContinueMode()).isEqualTo(TurnContinueMode.APPEND);
    }

    @Test
    void process_lengthTruncatedWithJson_schedulesContinue() {
        String text = "```json\n{\"message\":\"\",\"draftYaml\":\"writer:\\n  type: csv\",\"draftComplete\":true}\n";

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text, FinishReason.LENGTH), events::add);

        assertThat(needsContinue).isTrue();
        assertThat(session.getContinueMode()).isEqualTo(TurnContinueMode.APPEND);
    }

    @Test
    void process_parseFailure_smallDraft_schedulesRepair() {
        String yaml = "writer:\n  type: csv\n  bad";
        String text = jsonBlock("", yaml, true);
        when(webClient.validateYaml(anyString()))
                .thenReturn(ValidationResult.fail(
                        List.of("Failed to parse YAML content: bad yaml")));

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text, FinishReason.STOP), events::add);

        assertThat(needsContinue).isTrue();
        assertThat(session.getContinueMode()).isEqualTo(TurnContinueMode.REPAIR);
    }

    @Test
    void process_semanticValidationFailure_emitsValidationError() {
        String yaml = "writer:\n  type: csv";
        String text = jsonBlock("", yaml, true);
        when(webClient.validateYaml(anyString()))
                .thenReturn(ValidationResult.fail(List.of("缺少 tables")));

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text, FinishReason.STOP), events::add);

        assertThat(needsContinue).isFalse();
        assertThat(session.isDraftValidated()).isFalse();
        assertThat(events).anyMatch(event -> "validation_error".equals(event.getEvent()));
    }

    @Test
    void process_staleContinue_stopsAutoContinue() {
        settings.setMinDraftGrowthChars(32);
        settings.setMaxTurnContinues(3);
        String yaml = "writer:\n  type: csv\n  bad";
        String text = jsonBlock("", yaml, true);
        when(webClient.validateYaml(anyString()))
                .thenReturn(ValidationResult.fail(
                        List.of("Failed to parse YAML content: bad yaml")));

        session.setDraftYaml(yaml);
        session.incrementTurnContinueAttempts();
        session.setLastContinueDraftChars(yaml.length());

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text, FinishReason.STOP), events::add);

        assertThat(needsContinue).isFalse();
        assertThat(session.isDraftStopNotified()).isTrue();
    }

    @Test
    void process_alreadyValidated_skipsRevalidation() {
        String existing = "writer:\n  type: csv\ntables:\n  - name: t1";
        session.setDraftYaml(existing);
        session.setDraftValidated(true);
        String text = jsonBlock("已复制", "---", true);

        List<SseEvent> events = new ArrayList<>();
        boolean needsContinue = processor.process(session, chatResponse(text, FinishReason.STOP), events::add);

        assertThat(needsContinue).isFalse();
        assertThat(session.isDraftValidated()).isTrue();
        assertThat(events).isEmpty();
    }

    private static ChatResponse chatResponse(String text, FinishReason finishReason) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .finishReason(finishReason)
                .build();
    }

    private static String jsonBlock(String message, String draftYaml, boolean draftComplete) {
        String escapedYaml = draftYaml.replace("\n", "\\n").replace("\"", "\\\"");
        return """
                ```json
                {
                  "message": "%s",
                  "draftYaml": "%s",
                  "draftComplete": %s
                }
                ```
                """
                .formatted(message, escapedYaml, draftComplete);
    }
}
