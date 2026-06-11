package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class PhoneGenerator extends AbstractValueGenerator {

    public PhoneGenerator() {
        super("phone");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        String region = String.valueOf(config.getOrDefault("region", "cn")).toLowerCase();
        return switch (region) {
            case "cn" -> cnMobile();
            default -> throw new IllegalArgumentException("Unsupported phone region: " + region);
        };
    }

    private static String cnMobile() {
        int second = ThreadLocalRandom.current().nextInt(3, 10);
        StringBuilder builder = new StringBuilder(11);
        builder.append('1').append(second);
        for (int i = 0; i < 9; i++) {
            builder.append(ThreadLocalRandom.current().nextInt(10));
        }
        return builder.toString();
    }
}
