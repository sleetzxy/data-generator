package com.datagenerator.ai.agent.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Job 生成 Agent 的结构化单轮输出。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftResult(
        @JsonProperty("message") String message,
        @JsonProperty("draftYaml") String draftYaml,
        @JsonProperty("draftComplete") boolean draftComplete) implements AgentTurnResult {

    public DraftResult {
        message = message != null ? message : "";
        draftYaml = draftYaml != null ? draftYaml : "";
    }
}
