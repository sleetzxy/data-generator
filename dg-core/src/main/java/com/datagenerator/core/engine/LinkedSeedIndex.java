package com.datagenerator.core.engine;

import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.ReferenceDefinition;
import com.datagenerator.core.model.SeedDefinition;
import com.datagenerator.core.model.SeedLinkDefinition;
import com.datagenerator.core.model.SeedLinkSourceDefinition;
import com.datagenerator.core.model.YamlConfigLoader;
import com.datagenerator.spi.model.DataRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 启动时一次性加载 link seed 数据并建立索引，生成阶段仅做内存查找。
 * <p>从属 seed 须在 {@code link.match} 或 {@code link.sources} 中声明匹配规则，
 * {@code reader.query} 写纯 SQL 预加载数据。</p>
 */
public class LinkedSeedIndex {

    private final ReferenceDataLoader referenceDataLoader;
    private final YamlConfigLoader configLoader;
    private final Map<String, Map<Object, DataRow>> equalsIndex = new ConcurrentHashMap<>();
    private final Map<String, PathLinkedSeedMatcher> pathMatchers = new ConcurrentHashMap<>();

    public LinkedSeedIndex(ReferenceDataLoader referenceDataLoader, YamlConfigLoader configLoader) {
        this.referenceDataLoader = referenceDataLoader;
        this.configLoader = configLoader;
    }

    /** 预加载所有 link seed，SQL 仅执行一次。 */
    public void preload(List<SeedDefinition> linkedSeeds) {
        for (SeedDefinition seed : linkedSeeds) {
            if (seed.isRoot() || !seed.getTemplate().isEmpty()) {
                continue;
            }
            preloadSeed(seed);
        }
    }

    /** 按父 seed 行的关联列值查找已预加载的从属 seed 行。 */
    public DataRow lookup(SeedDefinition seed, Object linkId) {
        if (linkId == null) {
            return new DataRow();
        }

        Map<Object, DataRow> equalsRows = equalsIndex.get(seed.getName());
        if (equalsRows != null) {
            DataRow row = equalsRows.get(normalizeKey(linkId));
            return row == null ? new DataRow() : row;
        }

        PathLinkedSeedMatcher matcher = pathMatchers.get(seed.getName());
        if (matcher != null) {
            DataRow row = matcher.match(linkId);
            return row == null ? new DataRow() : row;
        }

        return new DataRow();
    }

    private void preloadSeed(SeedDefinition seed) {
        SeedLinkDefinition link = seed.getLink();
        if (link == null) {
            return;
        }

        if (!link.getSources().isEmpty()) {
            preloadFromSources(seed, link);
            return;
        }

        if (link.getMatch() != null && !link.getMatch().isBlank()) {
            preloadFromExplicitMatch(seed, link);
            return;
        }

        throw new ConfigLoadException(
                "Seed '" + seed.getName() + "' 的 link seed 须声明 match 或 sources");
    }

    private void preloadFromExplicitMatch(SeedDefinition seed, SeedLinkDefinition link) {
        String query = requireLoadQuery(seed, resolveQuery(seed));
        LinkMatchMode mode = parseMatchMode(link.getMatch());
        switch (mode) {
            case EQUALS -> preloadEqualsRows(seed, query, link.resolveLocalColumn());
            case PATH -> preloadPathRows(seed, query, link.resolveLocalColumn(), "segment");
            case CONTAINS -> preloadContainsRows(seed, query, link.resolveLocalColumn(), "node");
            default -> throw new IllegalStateException("Seed '" + seed.getName() + "' 不支持的 link.match: " + link.getMatch());
        }
    }

    private void preloadFromSources(SeedDefinition seed, SeedLinkDefinition link) {
        PathLinkedSeedMatcher matcher = new PathLinkedSeedMatcher();
        for (SeedLinkSourceDefinition source : link.getSources()) {
            if (source.getMatch() == null || source.getMatch().isBlank()) {
                throw new ConfigLoadException("Seed '" + seed.getName() + "' link.sources 缺少 match");
            }
            if (source.getColumn() == null || source.getColumn().isBlank()) {
                throw new ConfigLoadException("Seed '" + seed.getName() + "' link.sources 缺少 column");
            }
            String query = requireLoadQuery(seed, source.resolveLoadQuery());
            LinkMatchMode mode = parseMatchMode(source.getMatch());
            List<DataRow> rows = loadRows(seed, query);
            String profile = source.resolveProfile();
            switch (mode) {
                case EQUALS -> throw new ConfigLoadException(
                        "Seed '" + seed.getName() + "' 多数据源 link 暂不支持 equals，请拆成独立 seed");
                case PATH -> matcher.addPathRows(profile, rows, source.getColumn());
                case CONTAINS -> matcher.addContainsRows(profile, rows, source.getColumn());
                default -> throw new ConfigLoadException(
                        "Seed '" + seed.getName() + "' 不支持的 link source match: " + source.getMatch());
            }
        }
        pathMatchers.put(seed.getName(), matcher);
    }

    private void preloadPathRows(SeedDefinition seed, String query, String column, String profile) {
        PathLinkedSeedMatcher matcher = new PathLinkedSeedMatcher();
        List<DataRow> rows = loadRows(seed, query);
        matcher.addPathRows(profile, rows, column);
        pathMatchers.put(seed.getName(), matcher);
    }

    private void preloadContainsRows(SeedDefinition seed, String query, String column, String profile) {
        PathLinkedSeedMatcher matcher = new PathLinkedSeedMatcher();
        List<DataRow> rows = loadRows(seed, query);
        matcher.addContainsRows(profile, rows, column);
        pathMatchers.put(seed.getName(), matcher);
    }

    private void preloadEqualsRows(SeedDefinition seed, String query, String indexColumn) {
        List<DataRow> rows = loadRows(seed, query);
        Map<Object, DataRow> indexed = new HashMap<>();
        for (DataRow row : rows) {
            Object key = row.get(indexColumn);
            if (key != null) {
                indexed.putIfAbsent(normalizeKey(key), row);
            }
        }
        equalsIndex.put(seed.getName(), indexed);
    }

    private static String requireLoadQuery(SeedDefinition seed, String query) {
        if (query == null || query.isBlank()) {
            throw new ConfigLoadException("Seed '" + seed.getName() + "' 的 link seed 需要 reader.query");
        }
        if (query.contains(":link_id") || query.contains(":link.")) {
            throw new ConfigLoadException(
                    "Seed '" + seed.getName() + "' 的 link seed 不支持 SQL 占位符，请在 link 中声明 match/sources");
        }
        return query.trim();
    }

    private static LinkMatchMode parseMatchMode(String match) {
        if (match == null || match.isBlank()) {
            return LinkMatchMode.UNSUPPORTED;
        }
        return switch (match.trim().toLowerCase(Locale.ROOT)) {
            case "equals", "equal", "eq" -> LinkMatchMode.EQUALS;
            case "path" -> LinkMatchMode.PATH;
            case "contains", "contain", "like" -> LinkMatchMode.CONTAINS;
            default -> LinkMatchMode.UNSUPPORTED;
        };
    }

    private List<DataRow> loadRows(SeedDefinition seed, String query) {
        String readerType = resolveReaderType(seed);
        return referenceDataLoader.loadRows(readerType, buildReaderConfig(seed, query));
    }

    private Map<String, Object> buildReaderConfig(SeedDefinition seed, String query) {
        Map<String, Object> readerMap = new HashMap<>(resolveReaderMap(seed));
        readerMap.put("query", query);
        Map<String, Object> config = new HashMap<>();
        config.put("reader", readerMap);
        return config;
    }

    private Map<String, Object> resolveReaderMap(SeedDefinition seed) {
        if (!seed.getReader().isEmpty()) {
            return new HashMap<>(seed.getReader());
        }
        if (seed.getReference() != null && !seed.getReference().isBlank() && configLoader != null) {
            ReferenceDefinition reference = configLoader.loadReference(seed.getReference());
            return new HashMap<>(reference.getReader());
        }
        throw new IllegalArgumentException("Seed '" + seed.getName() + "' requires reader or reference");
    }

    private String resolveQuery(SeedDefinition seed) {
        Map<String, Object> readerMap = resolveReaderMap(seed);
        Object query = readerMap.get("query");
        return query == null ? null : String.valueOf(query);
    }

    private String resolveReaderType(SeedDefinition seed) {
        Map<String, Object> readerMap = resolveReaderMap(seed);
        Object type = readerMap.get("type");
        return type == null ? "postgresql" : String.valueOf(type);
    }

    static Object normalizeKey(Object value) {
        if (value instanceof Number number) {
            if (value instanceof Long || value instanceof Integer || value instanceof Short) {
                return number.longValue();
            }
            if (value instanceof Double || value instanceof Float) {
                double d = number.doubleValue();
                if (d == Math.rint(d)) {
                    return (long) d;
                }
            }
        }
        return value;
    }

    enum LinkMatchMode {
        EQUALS,
        PATH,
        CONTAINS,
        UNSUPPORTED
    }

    /**
     * path / nodeids 关联匹配：启动时加载全表，生成时按父 seed 关联值在内存中匹配首行。
     */
    static final class PathLinkedSeedMatcher {

        private final List<SegmentSource> pathSources = new ArrayList<>();
        private final List<SegmentSource> containsSources = new ArrayList<>();

        void addPathRows(String profile, List<DataRow> rows, String pathColumn) {
            pathSources.add(new SegmentSource(profile, rows, pathColumn));
        }

        void addContainsRows(String profile, List<DataRow> rows, String containsColumn) {
            containsSources.add(new SegmentSource(profile, rows, containsColumn));
        }

        DataRow match(Object linkId) {
            String linkKey = String.valueOf(linkId);
            for (SegmentSource source : pathSources) {
                for (DataRow row : source.rows()) {
                    Object pathValue = row.get(source.column());
                    if (pathValue != null && pathMatches(String.valueOf(pathValue), linkKey)) {
                        return mapResult(row, source.profile());
                    }
                }
            }
            for (SegmentSource source : containsSources) {
                for (DataRow row : source.rows()) {
                    Object nodeIds = row.get(source.column());
                    if (nodeIds != null && String.valueOf(nodeIds).contains(linkKey)) {
                        return mapResult(row, source.profile());
                    }
                }
            }
            return null;
        }

        private static DataRow mapResult(DataRow row, String profile) {
            if ("node".equalsIgnoreCase(profile)) {
                DataRow mapped = new DataRow();
                mapped.set("big_roadclid", row.get("nodeid"));
                mapped.set("big_road_name", row.get("name"));
                mapped.set("big_froadname", "");
                mapped.set("big_troadname", "");
                mapped.set("big_fnode", "");
                mapped.set("big_tnode", "");
                mapped.set("big_length", "");
                return mapped;
            }
            DataRow mapped = new DataRow();
            mapped.set("big_roadclid", row.get("bigroadid"));
            mapped.set("big_road_name", row.get("name"));
            mapped.set("big_froadname", row.get("froadname"));
            mapped.set("big_troadname", row.get("troadname"));
            mapped.set("big_fnode", row.get("fnode"));
            mapped.set("big_tnode", row.get("tnode"));
            mapped.set("big_length", row.get("length"));
            return mapped;
        }

        static boolean pathMatches(String path, String linkId) {
            return path.equals(linkId)
                    || path.startsWith(linkId + ",")
                    || path.startsWith(linkId + ";")
                    || path.contains(";" + linkId + ",")
                    || path.contains("," + linkId + ",")
                    || path.contains(";" + linkId + ";")
                    || path.contains("," + linkId + ";")
                    || path.endsWith("," + linkId)
                    || path.endsWith(";" + linkId);
        }

        private record SegmentSource(String profile, List<DataRow> rows, String column) {
        }
    }
}
