package com.datagenerator.plugins.postgresql;

import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * 将生成器产出的字符串值转换为 PostgreSQL JDBC 可识别的 Java 类型。
 */
final class PostgreSqlParameterBinder {

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

    private PostgreSqlParameterBinder() {
    }

    static Object prepareValue(Object value) {
        return prepareValue(null, value);
    }

    static Object prepareValue(String columnName, Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (isGeometryColumn(columnName) && looksLikeWkt(trimmed)) {
            return toGeometry(trimmed);
        }
        if (looksLikeDateTime(trimmed)) {
            return parseDateTime(trimmed);
        }
        return text;
    }

    private static boolean isGeometryColumn(String columnName) {
        return columnName != null && "geom".equalsIgnoreCase(columnName);
    }

    private static boolean looksLikeWkt(String text) {
        return text.regionMatches(true, 0, "POINT", 0, 5)
                || text.regionMatches(true, 0, "LINESTRING", 0, 10)
                || text.regionMatches(true, 0, "POLYGON", 0, 7)
                || text.regionMatches(true, 0, "MULTI", 0, 5)
                || text.regionMatches(true, 0, "GEOMETRYCOLLECTION", 0, 19);
    }

    private static PGobject toGeometry(String wkt) {
        try {
            PGobject geometry = new PGobject();
            geometry.setType("geometry");
            geometry.setValue(wkt);
            return geometry;
        } catch (SQLException exception) {
            throw new IllegalArgumentException("Invalid geometry WKT: " + wkt, exception);
        }
    }

    private static boolean looksLikeDateTime(String text) {
        return text.length() >= 10
                && Character.isDigit(text.charAt(0))
                && text.charAt(4) == '-'
                && text.charAt(7) == '-';
    }

    private static LocalDateTime parseDateTime(String text) {
        if (text.length() == 10) {
            return LocalDate.parse(text).atStartOfDay();
        }
        return LocalDateTime.parse(text, FLEXIBLE_DATE_TIME);
    }
}
