package com.datagenerator.ai.agent.result;

import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.SseEventFactory;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.TurnContinueMode;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.tool.impl.model.DgWebModels.ValidationResult;
import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import com.datagenerator.ai.util.DraftYamlMetrics;
import com.datagenerator.ai.util.ResponseFinishReasons;
import com.datagenerator.ai.util.YamlValidationHelper;
import com.datagenerator.ai.web.dto.common.SseEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 处理 Agent 结构化输出：合并草稿、校验、决定是否续写。 */
public class DraftResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(DraftResultProcessor.class);

    private final DataGeneratorWebClient webClient;
    private final AgentIoLogger ioLogger;
    private final AiProperties.DraftContinueProperties settings;

    public DraftResultProcessor(
            DataGeneratorWebClient webClient,
            AgentIoLogger ioLogger,
            AiProperties.DraftContinueProperties settings) {
        this.webClient = webClient;
        this.ioLogger = ioLogger != null ? ioLogger : AgentIoLogger.disabled();
        this.settings = settings != null ? settings : new AiProperties.DraftContinueProperties();
    }

    public boolean process(AgentSession session, ChatResponse response, Consumer<SseEvent> emitter) {
        String fullText = response.aiMessage() != null ? response.aiMessage().text() : "";
        DraftResultParser.MergeResult merge = DraftResultParser.merge(
                fullText, session.getDraftYaml(), session.getContinueMode());
        boolean lengthTruncated = ResponseFinishReasons.isLengthTruncated(response);
        boolean hasStructuredMarker = fullText.contains("```json");

        merge.merged()
                .map(DraftResult::draftYaml)
                .filter(yaml -> !yaml.isBlank())
                .ifPresent(session::setDraftYaml);

        boolean pendingContinue = merge.incomplete()
                || (lengthTruncated && hasStructuredMarker && merge.merged().isPresent());
        session.setContinueMode(TurnContinueMode.NONE);
        Optional<String> validatedYaml = Optional.empty();

        if (!pendingContinue && merge.merged().isPresent()) {
            String yaml = session.getDraftYaml();
            if (yaml != null && !yaml.isBlank()) {
                if (session.isDraftValidated()) {
                    validatedYaml = Optional.of(yaml);
                } else {
                int draftChars = yaml.length();
                int tableCount = DraftYamlMetrics.countTables(yaml);
                ValidationResult result = webClient.validateYaml(yaml);
                if (YamlValidationHelper.isParseFailure(result)) {
                    session.setDraftIncomplete(true);
                    session.setDraftValidated(false);
                    pendingContinue = decideParseFailureContinue(
                            session, merge, lengthTruncated, draftChars, tableCount);
                } else if (result.valid()) {
                    session.setDraftIncomplete(false);
                    session.setDraftValidated(true);
                    validatedYaml = Optional.of(yaml);
                } else {
                    session.setDraftValidated(false);
                    log.warn(
                            "Draft YAML failed validation for session {}: {}",
                            session.getSessionId(),
                            result.errors());
                    emitter.accept(SseEventFactory.validationError(result.errors()));
                    notifyDraftStop(session, "semantic validation failed");
                }
                }
            }
        } else if (pendingContinue) {
            session.setDraftIncomplete(true);
            session.setContinueMode(TurnContinueMode.APPEND);
            log.info("Structured output incomplete for session {}, scheduling auto-continue", session.getSessionId());
        } else {
            session.setDraftIncomplete(false);
        }

        if (pendingContinue) {
            session.setLastContinueDraftChars(
                    merge.merged()
                            .map(DraftResult::draftYaml)
                            .map(String::length)
                            .orElse(session.getLastContinueDraftChars()));
        }

        boolean willContinue =
                pendingContinue && session.getTurnContinueAttempts() < settings.getMaxTurnContinues();
        validatedYaml.ifPresent(yaml -> logValidatedDraft(session, yaml, willContinue));
        ioLogger.logTurnResultMerge(
                session.getSessionId(),
                merge.incomplete(),
                lengthTruncated,
                session.getDraftYaml(),
                willContinue,
                session.getContinueMode().name(),
                session.getTurnContinueAttempts());

        return willContinue;
    }

    private void logValidatedDraft(AgentSession session, String yaml, boolean willContinue) {
        if (willContinue) {
            log.info(
                    "Validated draft for session {} ({}), deferring until auto-continue completes",
                    session.getSessionId(),
                    DraftYamlMetrics.formatStats(yaml));
            return;
        }
        log.info(
                "Validated draft ready for session {} ({})",
                session.getSessionId(),
                DraftYamlMetrics.formatStats(yaml));
    }

    private boolean decideParseFailureContinue(
            AgentSession session,
            DraftResultParser.MergeResult merge,
            boolean lengthTruncated,
            int draftChars,
            int tableCount) {
        log.info(
                "Draft YAML parse failure for session {} ({} tables, {} chars, incomplete={}, lengthTruncated={})",
                session.getSessionId(),
                tableCount,
                draftChars,
                merge.incomplete(),
                lengthTruncated);

        if (tableCount > settings.getMaxTablesAutoContinue()) {
            notifyDraftStop(session, "parse failure, table count " + tableCount + " exceeds limit");
            return false;
        }
        if (isStaleContinue(session, draftChars)) {
            notifyDraftStop(session, "parse failure, stale auto-continue");
            return false;
        }
        if (merge.incomplete() || lengthTruncated) {
            session.setContinueMode(TurnContinueMode.APPEND);
            log.info("Scheduling append continue for session {} after parse failure", session.getSessionId());
            return true;
        }
        if (draftChars <= settings.getRepairMaxChars() && tableCount <= settings.getRepairMaxTables()) {
            session.setContinueMode(TurnContinueMode.REPAIR);
            log.info("Scheduling repair continue for session {} after parse failure", session.getSessionId());
            return true;
        }
        notifyDraftStop(session, "parse failure, closed draft too large to repair");
        return false;
    }

    private boolean isStaleContinue(AgentSession session, int draftChars) {
        if (session.getTurnContinueAttempts() <= 0 || session.getLastContinueDraftChars() < 0) {
            return false;
        }
        return draftChars - session.getLastContinueDraftChars() < settings.getMinDraftGrowthChars();
    }

    private static void notifyDraftStop(AgentSession session, String reason) {
        session.setDraftStopNotified(true);
        log.info("Draft auto-continue stopped for session {}: {}", session.getSessionId(), reason);
    }
}
