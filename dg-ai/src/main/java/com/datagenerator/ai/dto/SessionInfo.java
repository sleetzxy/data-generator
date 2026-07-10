package com.datagenerator.ai.dto;

import java.util.List;

/**
 * 会话摘要 DTO，用于会话列表接口返回。
 */
public record SessionInfo(
        String chatId,
        String title,
        int messageCount,
        String updatedAt) {

    /**
     * 会话列表 API 响应包装。
     */
    public record ListResponse(List<SessionInfo> sessions) {}
}
