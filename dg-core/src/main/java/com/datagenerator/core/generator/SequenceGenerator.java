package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SequenceGenerator extends AbstractValueGenerator {

    private final ConcurrentHashMap<StateKey, AtomicLong> counters = new ConcurrentHashMap<>();

    public SequenceGenerator() {
        super("sequence");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        long start = toLong(config.getOrDefault("start", 0L));
        long step = toLong(config.getOrDefault("step", 1L));
        StateKey key = new StateKey(ctx.tableName() == null ? "" : ctx.tableName(), start, step);
        AtomicLong counter = counters.computeIfAbsent(key, ignored -> new AtomicLong(start));
        return counter.getAndAdd(step);
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private record StateKey(String tableName, long start, long step) {
    }
}
