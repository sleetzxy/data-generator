package com.datagenerator.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagenerator.ai.prompt.provider.TemplatePromptProvider;
import com.datagenerator.ai.prompt.templates.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptTemplateProviderTest {

    private TemplatePromptProvider promptProvider;

    @BeforeEach
    void setUp() {
        promptProvider = new TemplatePromptProvider(new PromptTemplateLoader());
    }

    @Test
    void resolveSystemPrompt_includesOutputFormat() {
        String prompt = promptProvider.resolveSystemPrompt("job-generator");

        assertThat(prompt).contains("不臆造");
        assertThat(prompt).contains("validateDraftJobYaml");
        assertThat(prompt).contains("\"draftYaml\"");
        assertThat(prompt).contains("draftComplete");
    }

    @Test
    void resolveSystemPrompt_unknownAgent_throws() {
        assertThatThrownBy(() -> promptProvider.resolveSystemPrompt("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No prompt templates");
    }
}
