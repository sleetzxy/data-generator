package com.datagenerator.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentPromptTest {

    private AgentPrompt agentPrompt;

    @BeforeEach
    void setUp() {
        agentPrompt = new AgentPrompt();
    }

    @Test
    void loadSystemPrompt_assemblesAllParts() {
        String prompt = agentPrompt.loadSystemPrompt("job-generator");

        assertThat(prompt).isNotBlank();
        // 验证四个 .md 文件的关键内容都存在
        assertThat(prompt).contains("Data Generator");           // system.md
        assertThat(prompt).contains("```yaml");                  // output-format.md
        assertThat(prompt).contains("saveDraftJobDefinition");   // overlay.md
        assertThat(prompt).contains("strategy:");                // reference.md
    }

    @Test
    void loadSystemPrompt_unknownAgent_throws() {
        assertThatThrownBy(() -> agentPrompt.loadSystemPrompt("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No prompt templates");
    }
}
