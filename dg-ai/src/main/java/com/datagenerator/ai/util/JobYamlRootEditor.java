package com.datagenerator.ai.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/** 编辑 Job YAML 顶层字段（id / name / schedule 等）。 */
public final class JobYamlRootEditor {

    private static final Yaml YAML = new Yaml();

    private JobYamlRootEditor() {
    }

    public static String removeRootKeys(String yaml, String... keys) {
        if (yaml == null || yaml.isBlank() || keys == null || keys.length == 0) {
            return yaml;
        }
        Map<String, Object> root = parseRoot(yaml);
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                root.remove(key.trim());
            }
        }
        return dumpRoot(root);
    }

    public static String setRootKey(String yaml, String key, Object value) {
        if (yaml == null || yaml.isBlank() || key == null || key.isBlank()) {
            return yaml;
        }
        Map<String, Object> root = parseRoot(yaml);
        root.put(key.trim(), value);
        return dumpRoot(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRoot(String yaml) {
        Object loaded = YAML.load(yaml);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Job YAML 顶层必须是 mapping");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        map.forEach((key, value) -> root.put(String.valueOf(key), value));
        return root;
    }

    private static String dumpRoot(Map<String, Object> root) {
        return YAML.dump(root);
    }

    /** 供测试：是否包含指定顶层键。 */
    static boolean hasRootKey(String yaml, String key) {
        Map<String, Object> root = parseRoot(yaml);
        return root.containsKey(key);
    }

    /** 供测试：顶层键集合。 */
    static Set<String> rootKeys(String yaml) {
        return parseRoot(yaml).keySet();
    }
}
