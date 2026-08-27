package com.datagenerator.web.dto;

import java.util.List;

public record TaskConfigValidationResponse(boolean valid, List<String> errors) {

    public static TaskConfigValidationResponse ok() {
        return new TaskConfigValidationResponse(true, List.of());
    }

    public static TaskConfigValidationResponse fail(List<String> errors) {
        return new TaskConfigValidationResponse(false, errors);
    }
}
