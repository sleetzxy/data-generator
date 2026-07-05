package com.datagenerator.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 对话请求体。
 */
public record ChatRequest(
    @NotBlank(message = "消息内容不能为空")
    String content,

    String mode  // token | verbose，默认 token
) {}
