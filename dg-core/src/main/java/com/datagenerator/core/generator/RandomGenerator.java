package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class RandomGenerator extends AbstractValueGenerator {

    public RandomGenerator() {
        super("random");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        String type = String.valueOf(config.getOrDefault("type", "int"));
        return switch (type.toLowerCase()) {
            case "int" -> randomInt(config);
            case "long" -> randomLong(config);
            case "double" -> randomDouble(config);
            case "string" -> randomString(config);
            case "date" -> randomDate(config);
            case "datetime" -> randomDateTime(config);
            default -> throw new IllegalArgumentException("Unsupported random type: " + type);
        };
    }

    private int randomInt(Map<String, Object> config) {
        int min = toInt(config.getOrDefault("min", 0));
        int max = toInt(config.getOrDefault("max", Integer.MAX_VALUE));
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private long randomLong(Map<String, Object> config) {
        long min = toLong(config.getOrDefault("min", 0L));
        long max = toLong(config.getOrDefault("max", Long.MAX_VALUE));
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private double randomDouble(Map<String, Object> config) {
        double min = toDouble(config.getOrDefault("min", 0.0));
        double max = toDouble(config.getOrDefault("max", 1.0));
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    private String randomString(Map<String, Object> config) {
        int minLength = toInt(config.getOrDefault("minLength", 8));
        int maxLength = toInt(config.getOrDefault("maxLength", minLength));
        int length = ThreadLocalRandom.current().nextInt(minLength, maxLength + 1);
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private Object randomDate(Map<String, Object> config) {
        LocalDate min = parseDate(config.getOrDefault("min", "1970-01-01"));
        LocalDate max = parseDate(config.getOrDefault("max", "2099-12-31"));
        long days = ChronoUnit.DAYS.between(min, max);
        long offset = days == 0 ? 0 : ThreadLocalRandom.current().nextLong(days + 1);
        return formatTemporal(config, min.plusDays(offset));
    }

    private Object randomDateTime(Map<String, Object> config) {
        LocalDateTime min = parseDateTime(config.getOrDefault("min", "1970-01-01 00:00:00"));
        LocalDateTime max = parseDateTime(config.getOrDefault("max", "2099-12-31 23:59:59"));
        long seconds = ChronoUnit.SECONDS.between(min, max);
        long offset = seconds == 0 ? 0 : ThreadLocalRandom.current().nextLong(seconds + 1);
        return formatTemporal(config, min.plusSeconds(offset));
    }

    private static Object formatTemporal(Map<String, Object> config, LocalDate value) {
        String pattern = temporalFormat(config);
        if (pattern == null) {
            return value;
        }
        return value.format(DateTimeFormatter.ofPattern(pattern));
    }

    private static Object formatTemporal(Map<String, Object> config, LocalDateTime value) {
        String pattern = temporalFormat(config);
        if (pattern == null) {
            return value;
        }
        return value.format(DateTimeFormatter.ofPattern(pattern));
    }

    private static String temporalFormat(Map<String, Object> config) {
        Object format = config.get("format");
        if (format == null || String.valueOf(format).isBlank()) {
            return null;
        }
        return String.valueOf(format);
    }

    private static final DateTimeFormatter FLEXIBLE_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .optionalStart()
            .appendLiteral(' ')
            .appendPattern("HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .optionalEnd()
            .toFormatter();

    private static LocalDateTime parseDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        String text = String.valueOf(value).trim();
        if (text.length() == 10) {
            return LocalDate.parse(text).atStartOfDay();
        }
        return LocalDateTime.parse(text, FLEXIBLE_DATE_TIME);
    }

    private static LocalDate parseDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
}
