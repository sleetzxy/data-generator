package com.datagenerator.ai.util;

import com.datagenerator.ai.tool.impl.model.DgWebModels.ValidationResult;
import java.util.List;

/** YAML 校验结果辅助判断。 */
public final class YamlValidationHelper {

    private static final String PARSE_ERROR_MARKER = "Failed to parse YAML content";

    private YamlValidationHelper() {
    }

    public static boolean isParseFailure(ValidationResult result) {
        if (result == null || result.valid()) {
            return false;
        }
        return isParseFailureMessage(result.errors());
    }

    public static boolean isParseFailureMessage(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return false;
        }
        return errors.stream().anyMatch(error -> error != null && error.contains(PARSE_ERROR_MARKER));
    }
}
