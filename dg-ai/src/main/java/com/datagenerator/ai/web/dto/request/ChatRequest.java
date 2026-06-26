package com.datagenerator.ai.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public class ChatRequest {

    @NotBlank
    private String content;

    public ChatRequest() {
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
