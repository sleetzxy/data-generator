package com.datagenerator.web.exception;

public class ReadOnlyScheduleException extends RuntimeException {

    public ReadOnlyScheduleException(String configPath) {
        super("Schedule is read-only for builtin job: " + configPath);
    }
}
