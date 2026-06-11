package com.datagenerator.core.generator;

import java.util.Map;

/**
 * 对任意生成策略的输出统一应用 generator 配置中的 {@code prefix} / {@code width}。
 */
public final class GeneratorOutputFormatter {

    private GeneratorOutputFormatter() {
    }

    public static Object apply(Object value, Map<String, Object> config) {
        if (value == null) {
            return null;
        }
        Object prefixValue = config.get("prefix");
        if (prefixValue == null || String.valueOf(prefixValue).isBlank()) {
            return value;
        }
        String prefix = String.valueOf(prefixValue);
        String body = formatBody(value, config);
        return prefix + body;
    }

    private static String formatBody(Object value, Map<String, Object> config) {
        if (value instanceof Number number && config.containsKey("width")) {
            int width = toInt(config.get("width"));
            return String.format("%0" + width + "d", number.longValue());
        }
        return String.valueOf(value);
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
