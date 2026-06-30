package com.datagenerator.web.dto;

import java.util.List;

public record JobYamlValidationResponse(boolean valid, List<String> errors) {

    public static JobYamlValidationResponse ok() {
        return new JobYamlValidationResponse(true, List.of());
    }

    public static JobYamlValidationResponse fail(List<String> errors) {
        return new JobYamlValidationResponse(false, errors);
    }
}
