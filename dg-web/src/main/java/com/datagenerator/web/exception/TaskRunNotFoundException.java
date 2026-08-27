package com.datagenerator.web.exception;

public class TaskRunNotFoundException extends RuntimeException {

    public TaskRunNotFoundException(String runId) {
        super("Task run not found: " + runId);
    }
}
