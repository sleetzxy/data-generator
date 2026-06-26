package com.datagenerator.ai.application.session;



import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.datagenerator.ai.tool.impl.model.DgWebModels.SchemaDetail;



public class AgentSession {



    private final String sessionId;

    private final String agentId;

    private final String toolSetId;

    private final String provider;

    private final Instant createdAt;

    private Instant lastActiveAt;

    private String draftYaml;

    private boolean draftIncomplete;

    private boolean draftValidated;

    private int turnContinueAttempts;

    /** 上次自动续写前的草稿字符数，用于检测续写无进展 */

    private int lastContinueDraftChars = -1;

    private TurnContinueMode continueMode = TurnContinueMode.NONE;

    /** onComplete 已向前端输出停止续写的说明，避免 handleComplete 重复提示 */

    private boolean draftStopNotified;

    /** 本轮已通过 saveDraftJobDefinition 写入控制台 */

    private boolean draftPersistedInTurn;

    /** 参考 Job 完整 YAML，按 fileName 缓存；对话 memory 仅存摘要 */

    private final Map<String, String> referenceYamls = new ConcurrentHashMap<>();

    /** 参考 Schema 详情，按名称缓存 */

    private final Map<String, SchemaDetail> referenceSchemas = new ConcurrentHashMap<>();

    /** 是否已有进行中的 SSE 对话轮次（含 auto-continue） */
    private final AtomicBoolean turnInProgress = new AtomicBoolean(false);

    /** 当前轮次是否已被取消（DELETE 会话或客户端断开 SSE） */
    private final AtomicBoolean turnCancelled = new AtomicBoolean(false);

    public AgentSession(
            String sessionId, String agentId, String toolSetId, String provider, Instant createdAt) {

        this.sessionId = sessionId;

        this.agentId = agentId;

        this.toolSetId = toolSetId;

        this.provider = provider;

        this.createdAt = createdAt;

        this.lastActiveAt = createdAt;

    }



    public String getSessionId() {

        return sessionId;

    }



    public String getAgentId() {

        return agentId;

    }



    public String getToolSetId() {

        return toolSetId;

    }



    public String getProvider() {

        return provider;

    }



    public Instant getCreatedAt() {

        return createdAt;

    }



    public Instant getLastActiveAt() {

        return lastActiveAt;

    }



    public void setLastActiveAt(Instant lastActiveAt) {

        this.lastActiveAt = lastActiveAt;

    }



    public String getDraftYaml() {

        return draftYaml;

    }



    public void setDraftYaml(String draftYaml) {

        this.draftYaml = draftYaml;

    }



    public boolean isDraftIncomplete() {

        return draftIncomplete;

    }



    public void setDraftIncomplete(boolean draftIncomplete) {

        this.draftIncomplete = draftIncomplete;

    }



    public boolean isDraftValidated() {

        return draftValidated;

    }



    public void setDraftValidated(boolean draftValidated) {

        this.draftValidated = draftValidated;

    }



    public int getTurnContinueAttempts() {

        return turnContinueAttempts;

    }



    public void incrementTurnContinueAttempts() {

        turnContinueAttempts++;

    }



    public void resetTurnContinueAttempts() {

        turnContinueAttempts = 0;

        lastContinueDraftChars = -1;

        continueMode = TurnContinueMode.NONE;

        draftStopNotified = false;

        draftPersistedInTurn = false;

    }



    public void beginUserTurn(String userMessage) {

        resetTurnContinueAttempts();

    }



    public int getLastContinueDraftChars() {

        return lastContinueDraftChars;

    }



    public void setLastContinueDraftChars(int lastContinueDraftChars) {

        this.lastContinueDraftChars = lastContinueDraftChars;

    }



    public TurnContinueMode getContinueMode() {

        return continueMode;

    }



    public void setContinueMode(TurnContinueMode continueMode) {

        this.continueMode = continueMode != null ? continueMode : TurnContinueMode.NONE;

    }



    public boolean isDraftStopNotified() {

        return draftStopNotified;

    }



    public void setDraftStopNotified(boolean draftStopNotified) {

        this.draftStopNotified = draftStopNotified;

    }



    public boolean isDraftPersistedInTurn() {

        return draftPersistedInTurn;

    }



    public void markDraftPersistedInTurn() {

        this.draftPersistedInTurn = true;

    }



    public void clearDraftPersistedInTurn() {

        this.draftPersistedInTurn = false;

    }



    public void putReferenceYaml(String fileName, String yaml) {

        if (fileName != null && yaml != null && !yaml.isBlank()) {

            referenceYamls.put(fileName.trim(), yaml);

        }

    }



    public Map<String, String> getReferenceYamls() {

        return Map.copyOf(referenceYamls);

    }



    public void putReferenceSchema(String name, SchemaDetail detail) {

        if (name != null && detail != null) {

            referenceSchemas.put(name.trim(), detail);

        }

    }



    public Map<String, SchemaDetail> getReferenceSchemas() {

        return Map.copyOf(referenceSchemas);

    }

    /** 尝试占用对话轮次；已有进行中的轮次时返回 false。 */
    public boolean tryBeginTurn() {
        return turnInProgress.compareAndSet(false, true);
    }

    /** 释放对话轮次占用并清除取消标志。 */
    public void endTurn() {
        turnInProgress.set(false);
        turnCancelled.set(false);
    }

    /** 标记当前轮次应停止（不释放占用，由 endTurn 统一释放）。 */
    public void requestTurnCancellation() {
        turnCancelled.set(true);
    }

    public boolean isTurnCancelled() {
        return turnCancelled.get();
    }

    public boolean isTurnInProgress() {
        return turnInProgress.get();
    }

}

