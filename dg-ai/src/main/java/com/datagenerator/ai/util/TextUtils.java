package com.datagenerator.ai.util;

public final class TextUtils {

    private TextUtils() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
