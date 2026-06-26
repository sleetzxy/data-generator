package com.datagenerator.ai.exception;

/** 同一会话已有进行中的对话轮次时抛出。 */
public class SessionConflictException extends RuntimeException {

    private final String sessionId;

    public SessionConflictException(String sessionId) {
        super("会话 " + sessionId + " 已有进行中的对话，请等待完成后再发送");
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
