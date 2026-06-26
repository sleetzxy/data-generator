package com.datagenerator.ai.prompt.provider;

import com.datagenerator.ai.prompt.templates.PromptTemplateLoader;

/** 从 {@code prompt/templates/} 加载 Agent 系统提示词。 */
public class TemplatePromptProvider implements PromptProvider {

    private final PromptTemplateLoader templateLoader;

    public TemplatePromptProvider(PromptTemplateLoader templateLoader) {
        this.templateLoader = templateLoader;
    }

    @Override
    public String resolveSystemPrompt(String agentId) {
        return templateLoader.loadSystemPrompt(agentId);
    }
}
