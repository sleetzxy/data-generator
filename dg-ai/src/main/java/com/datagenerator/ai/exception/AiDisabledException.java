package com.datagenerator.ai.exception;

public class AiDisabledException extends RuntimeException {

    public AiDisabledException() {
        super("AI agent is disabled");
    }

    public AiDisabledException(String message) {
        super(message);
    }
}
