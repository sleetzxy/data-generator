package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class EmailGenerator extends AbstractValueGenerator {

    private static final String LOCAL_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    public EmailGenerator() {
        super("email");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        int minLength = toInt(config.getOrDefault("minLength", 6));
        int maxLength = toInt(config.getOrDefault("maxLength", Math.max(minLength, 12)));
        String domain = String.valueOf(config.getOrDefault("domain", "example.com"));
        if (domain.isBlank() || "null".equals(domain)) {
            throw new IllegalArgumentException("email generator requires non-blank 'domain'");
        }
        return randomLocalPart(minLength, maxLength) + "@" + domain;
    }

    private static String randomLocalPart(int minLength, int maxLength) {
        int length = ThreadLocalRandom.current().nextInt(minLength, maxLength + 1);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(LOCAL_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(LOCAL_ALPHABET.length())));
        }
        return builder.toString();
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
