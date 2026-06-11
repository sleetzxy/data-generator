package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class IdCardGenerator extends AbstractValueGenerator {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
    private static final DateTimeFormatter BIRTH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 覆盖各省/直辖市/自治区代表性区划码（GB/T 2260 六位码）。 */
    private static final String[] AREA_CODES = {
            "110101", "110102", "110105", "110106", "110108",
            "120101", "120103", "120104",
            "130102", "130104", "130203", "130402", "130502",
            "140105", "140215", "140302",
            "150102", "150203", "150402",
            "210102", "210203", "210302", "210411",
            "220102", "220203", "220302",
            "230102", "230203", "230602",
            "310101", "310104", "310115", "310112",
            "320102", "320505", "320583", "320612",
            "330102", "330203", "330483", "330702",
            "340102", "340203", "340302",
            "350102", "350203", "350502",
            "360102", "360703", "360802",
            "370102", "370203", "370213", "370702",
            "410102", "410203", "410502",
            "420102", "420502", "420602",
            "430102", "430111", "430602",
            "440103", "440115", "440303", "440604", "440783",
            "450102", "450203", "450502",
            "460105", "460202",
            "500101", "500103", "500112",
            "510104", "510703", "510802",
            "520102", "520302",
            "530102", "530302", "532901",
            "540102",
            "610102", "610113", "610502",
            "620102", "620502",
            "630102",
            "640104", "640502",
            "650102", "650502", "652901",
    };

    public IdCardGenerator() {
        super("idcard");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        Object from = config.get("from");
        if (from != null && !String.valueOf(from).isBlank()) {
            return deriveFromRow(ctx, config, String.valueOf(from).trim());
        }
        String areaCode = resolveAreaCode(config);
        String birthDate = resolveBirthDate(config);
        int sequence = resolveSequence(config);
        String body17 = areaCode + birthDate + String.format("%03d", sequence);
        return body17 + calculateCheckDigit(body17);
    }

    private static Object deriveFromRow(GenerationContext ctx, Map<String, Object> config, String sourceField) {
        Object source = ctx.rowBeingBuilt().get(sourceField);
        if (source == null) {
            throw new IllegalStateException(
                    "idcard generator from '" + sourceField + "' is not available in the current row yet");
        }
        String idCard = String.valueOf(source);
        String part = resolvePart(config);
        return switch (part) {
            case "full" -> idCard;
            case "gender" -> deriveGender(idCard);
            case "age" -> deriveAge(idCard, config);
            case "birth_date" -> deriveBirthDate(idCard);
            default -> throw new IllegalArgumentException(
                    "idcard generator unknown part: " + part + " (supported: full, gender, age, birth_date)");
        };
    }

    private static String resolvePart(Map<String, Object> config) {
        Object part = config.get("part");
        if (part == null || String.valueOf(part).isBlank()) {
            return "full";
        }
        return String.valueOf(part).trim().toLowerCase();
    }

    static String deriveGender(String idCard) {
        validateIdCardLength(idCard);
        int digit = Character.digit(idCard.charAt(16), 10);
        return digit % 2 == 1 ? "1" : "2";
    }

    static String deriveAge(String idCard, Map<String, Object> config) {
        validateIdCardLength(idCard);
        int birthYear = Integer.parseInt(idCard.substring(6, 10));
        return String.valueOf(resolveBaseYear(config) - birthYear);
    }

    static String deriveBirthDate(String idCard) {
        validateIdCardLength(idCard);
        return idCard.substring(6, 10)
                + "-"
                + idCard.substring(10, 12)
                + "-"
                + idCard.substring(12, 14);
    }

    static char calculateCheckDigit(String body17) {
        if (body17.length() != 17 || !body17.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("ID card body must be 17 digits");
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (body17.charAt(i) - '0') * WEIGHTS[i];
        }
        return CHECK_CODES[sum % 11];
    }

    private static int resolveBaseYear(Map<String, Object> config) {
        Object value = config.get("baseYear");
        if (value == null || String.valueOf(value).isBlank()) {
            return LocalDate.now().getYear();
        }
        return Integer.parseInt(String.valueOf(value).trim());
    }

    private static void validateIdCardLength(String idCard) {
        if (idCard.length() < 17) {
            throw new IllegalArgumentException(
                    "idcard part derivation requires at least 17 characters, got: " + idCard.length());
        }
    }

    private static String resolveAreaCode(Map<String, Object> config) {
        Object value = config.get("areaCode");
        if (value == null || String.valueOf(value).isBlank()) {
            return randomAreaCode();
        }
        String areaCode = String.valueOf(value).trim();
        if (!areaCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("idcard generator areaCode must be 6 digits");
        }
        return areaCode;
    }

    private static String randomAreaCode() {
        return AREA_CODES[ThreadLocalRandom.current().nextInt(AREA_CODES.length)];
    }

    private static String resolveBirthDate(Map<String, Object> config) {
        Object fixed = config.get("birthDate");
        if (fixed != null && !String.valueOf(fixed).isBlank()) {
            return normalizeBirthDate(String.valueOf(fixed));
        }
        LocalDate min = parseBirthDate(config.getOrDefault("birthDateMin", "1970-01-01"));
        LocalDate max = parseBirthDate(config.getOrDefault("birthDateMax", "2005-12-31"));
        if (min.isAfter(max)) {
            throw new IllegalArgumentException("idcard generator birthDateMin must not be after birthDateMax");
        }
        long days = ChronoUnit.DAYS.between(min, max);
        long offset = days == 0 ? 0 : ThreadLocalRandom.current().nextLong(days + 1);
        return min.plusDays(offset).format(BIRTH_DATE);
    }

    private static String normalizeBirthDate(String value) {
        String trimmed = value.trim();
        if (trimmed.matches("\\d{8}")) {
            return trimmed;
        }
        return parseBirthDate(trimmed).format(BIRTH_DATE);
    }

    private static LocalDate parseBirthDate(Object value) {
        String text = String.valueOf(value).trim();
        if (text.length() == 8 && text.chars().allMatch(Character::isDigit)) {
            return LocalDate.parse(text, BIRTH_DATE);
        }
        return LocalDate.parse(text);
    }

    private static int resolveSequence(Map<String, Object> config) {
        int sequence = ThreadLocalRandom.current().nextInt(1, 1000);
        Object gender = config.get("gender");
        if (gender == null || String.valueOf(gender).isBlank()) {
            return sequence;
        }
        boolean male = "male".equalsIgnoreCase(String.valueOf(gender))
                || "m".equalsIgnoreCase(String.valueOf(gender))
                || "1".equals(String.valueOf(gender));
        boolean female = "female".equalsIgnoreCase(String.valueOf(gender))
                || "f".equalsIgnoreCase(String.valueOf(gender))
                || "0".equals(String.valueOf(gender));
        if (male && sequence % 2 == 0) {
            sequence = sequence == 999 ? 998 : sequence + 1;
        } else if (female && sequence % 2 == 1) {
            sequence = sequence == 1 ? 2 : sequence - 1;
        }
        return sequence;
    }
}
