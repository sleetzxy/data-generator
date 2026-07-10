package com.datagenerator.ai.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 配置草稿管理器 — 提供草稿文件操作与 YAML 合并能力。
 *
 * <p>每个草稿对应一个 workspace 中的目录结构：</p>
 * <pre>
 * drafts/
 *   &lt;draftId&gt;/
 *     header.yaml            — 配置头信息（除 tables/constraints/seeds 外的顶级字段）
 *     table_&lt;name&gt;/
 *       _meta.yaml           — table 元信息（除 fields 外）
 *       _fields_01.yaml      — table 字段定义（第一批次，≤30 字段）
 *       _fields_02.yaml      — table 字段定义（第二批次，可选）
 *       ...
 *     constraints.yaml       — 约束定义（可选）
 *     seeds.yaml             — 种子数据（可选）
 * </pre>
 *
 * <p>不直接调用 DgWebClient，仅通过 HarnessAgent 的 WorkspaceManager 进行文件读写。</p>
 */
@Component
public class ConfigDraftManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigDraftManager.class);

    /** 草稿根目录 */
    private static final String DRAFTS_DIR = "drafts";

    /** header 文件名 */
    private static final String HEADER_FILE = "header.yaml";

    /** table 元信息文件名 */
    private static final String META_FILE = "_meta.yaml";

    /** constraints 文件名 */
    private static final String CONSTRAINTS_FILE = "constraints.yaml";

    /** seeds 文件名 */
    private static final String SEEDS_FILE = "seeds.yaml";

    /** table 子目录前缀 */
    private static final String TABLE_PREFIX = "table_";

    /** fields 批处理文件前缀 */
    private static final String FIELDS_PREFIX = "_fields_";

    /** fields 批处理文件最大编号 */
    private static final int MAX_FIELD_BATCHES = 99;

    /** table 名索引文件，用于跨文件系统列出 table */
    private static final String TABLES_INDEX = "_tables.txt";

    private final ObjectProvider<HarnessAgent> harnessAgentProvider;

    /** WorkspaceManager 缓存引用，避免每次调用 getObject() */
    private volatile WorkspaceManager cachedWorkspaceManager;

    /** 每次调用创建新 Yaml 实例，避免多线程并发时的状态损坏（SnakeYAML 非线程安全） */
    private Yaml yaml() {
        return new Yaml();
    }

    /**
     * fields 追加操作的结果。
     *
     * @param batchNum        本次批次编号
     * @param batchCount      本批字段数
     * @param cumulativeCount 该 table 累计字段数
     */
    public record AppendFieldsResult(int batchNum, int batchCount, int cumulativeCount) {}

    public ConfigDraftManager(ObjectProvider<HarnessAgent> harnessAgentProvider) {
        this.harnessAgentProvider = harnessAgentProvider;
    }

    // ==================== WorkspaceManager 访问 ====================

    private WorkspaceManager workspaceManager() {
        WorkspaceManager wm = cachedWorkspaceManager;
        if (wm == null) {
            synchronized (this) {
                wm = cachedWorkspaceManager;
                if (wm == null) {
                    wm = harnessAgentProvider.getObject().getWorkspaceManager();
                    cachedWorkspaceManager = wm;
                }
            }
        }
        return wm;
    }

    // ==================== 路径与文件读写辅助 ====================

    /**
     * 构建草稿文件相对路径。
     *
     * @param draftId  草稿 ID
     * @param segments 路径片段
     * @return workspace 相对路径
     */
    private String draftPath(String draftId, String... segments) {
        // 防止路径穿越攻击：draftId 和 segments 中不得包含导航字符
        if (draftId.contains("..") || draftId.contains("/") || draftId.contains("\\")) {
            throw new IllegalArgumentException("draftId 包含非法字符: " + draftId);
        }
        for (String seg : segments) {
            if (seg.contains("..")) {
                throw new IllegalArgumentException("路径片段包含非法字符: " + seg);
            }
        }
        String joined = String.join("/", segments);
        return DRAFTS_DIR + "/" + draftId + (joined.isEmpty() ? "" : "/" + joined);
    }

    /**
     * 向草稿写入文件。
     *
     * @param rc       RuntimeContext
     * @param draftId  草稿 ID
     * @param fileName 文件名（相对于草稿根）
     * @param content  文件内容
     */
    private void writeFile(RuntimeContext rc, String draftId, String fileName, String content) {
        String path = draftPath(draftId, fileName);
        // 主路径：通过 WorkspaceManager 写入（ShellAwareOverlay / agent-scope 标准路径）
        workspaceManager().writeUtf8WorkspaceRelative(rc, path, content);
        // 双写：同时直写本地磁盘，确保跨 Tool 调用 / 跨 RuntimeContext 时文件可被 Files.readString 读到
        try {
            Path diskPath = workspaceManager().getWorkspace().resolve(path);
            java.nio.file.Files.createDirectories(diskPath.getParent());
            java.nio.file.Files.writeString(diskPath, content);
        } catch (Exception e) {
            log.warn("本地磁盘双写失败（WorkspaceManager 写入不受影响）: path={}", path, e);
        }
        log.debug("写入草稿文件: {}", path);
    }

    /**
     * 从草稿读取文件。
     *
     * @param rc       RuntimeContext
     * @param draftId  草稿 ID
     * @param fileName 文件名（相对于草稿根）
     * @return 文件内容，文件不存在时返回 null
     */
    private String readFile(RuntimeContext rc, String draftId, String fileName) {
        String path = draftPath(draftId, fileName);
        // 优先读本地磁盘（双写保证文件一定在磁盘上）
        try {
            Path diskPath = workspaceManager().getWorkspace().resolve(path);
            if (java.nio.file.Files.exists(diskPath)) {
                String content = java.nio.file.Files.readString(diskPath);
                if (content != null && !content.isBlank()) {
                    return content;
                }
            }
        } catch (Exception e) {
            log.debug("本地磁盘读取失败: path={}", path, e);
        }
        // 回退：通过 WorkspaceManager 读取（ShellAwareOverlay 场景作为补充）
        try {
            return workspaceManager().readManagedWorkspaceFileUtf8(rc, path);
        } catch (Exception e) {
            log.debug("WorkspaceManager 读取失败: path={}", path, e);
        }
        return null;
    }

    /**
     * 向草稿中指定 table 的子目录写入文件。
     *
     * @param rc        RuntimeContext
     * @param draftId   草稿 ID
     * @param tableName table 名
     * @param fileName  文件名（相对于 table 子目录）
     * @param content   文件内容
     */
    private void writeTableFile(RuntimeContext rc, String draftId, String tableName,
                                String fileName, String content) {
        writeFile(rc, draftId, TABLE_PREFIX + tableName + "/" + fileName, content);
    }

    /**
     * 从草稿中指定 table 的子目录读取文件。
     *
     * @param rc        RuntimeContext
     * @param draftId   草稿 ID
     * @param tableName table 名
     * @param fileName  文件名（相对于 table 子目录）
     * @return 文件内容，文件不存在时返回 null
     */
    private String readTableFile(RuntimeContext rc, String draftId, String tableName,
                                 String fileName) {
        return readFile(rc, draftId, TABLE_PREFIX + tableName + "/" + fileName);
    }

    // ==================== 草稿生命周期 ====================

    /**
     * 创建草稿，写入 header YAML。
     *
     * @param rc         RuntimeContext
     * @param draftId    草稿 ID
     * @param headerYaml header 部分 YAML（不可为空）
     * @throws IllegalArgumentException headerYaml 为空或 draftId 已存在时抛出
     */
    public void createDraft(RuntimeContext rc, String draftId, String headerYaml) {
        if (headerYaml == null || headerYaml.isBlank()) {
            throw new IllegalArgumentException("headerYaml 不能为空");
        }
        // 冲突检测：草稿已存在时报错，避免覆盖已有工作
        if (draftExists(rc, draftId)) {
            throw new IllegalArgumentException(
                    "草稿「" + draftId + "」已存在。如需重新开始，请先调用 discardConfigDraft 废弃旧草稿，"
                            + "或使用不同的 draftId。");
        }
        writeFile(rc, draftId, HEADER_FILE, headerYaml.trim());
        log.info("草稿已创建: draftId={}", draftId);
    }

    /**
     * 废弃草稿。先清理所有 table 子文件，再标记 header 和索引为空。
     * 确保同名新草稿不会被旧 table 残留文件阻塞创建。
     *
     * @param rc      RuntimeContext
     * @param draftId 草稿 ID
     */
    public void deleteDraft(RuntimeContext rc, String draftId) {
        // 先清理所有 table 文件，避免残留文件阻塞同名新草稿
        List<String> tables = listTableNames(rc, draftId);
        for (String tableName : tables) {
            removeTable(rc, draftId, tableName);
        }
        // 再标记 header 和索引为空（逻辑删除）
        writeFile(rc, draftId, HEADER_FILE, "");
        writeFile(rc, draftId, TABLES_INDEX, "");
        log.info("草稿已废弃: draftId={}", draftId);
    }

    /**
     * 检查草稿是否存在（header 非空即为存在）。
     *
     * @param rc      RuntimeContext
     * @param draftId 草稿 ID
     * @return true 如果草稿存在且 header 非空
     */
    public boolean draftExists(RuntimeContext rc, String draftId) {
        String header = readFile(rc, draftId, HEADER_FILE);
        return header != null && !header.isBlank();
    }

    /**
     * 预检 table YAML 中的字段数，用于提示是否应使用分批模式。
     * 兼容扁平格式（fields 在顶层）和标准格式（fields 在 schema 下）。
     *
     * @param tableYaml 完整 table YAML
     * @return fields 数量，解析失败时返回 0
     */
    @SuppressWarnings("unchecked")
    public int countFields(String tableYaml) {
        try {
            Object loaded = yaml().load(tableYaml);
            if (loaded instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) loaded;
                // 扁平格式：fields 在顶层
                Object cols = map.get("fields");
                if (cols instanceof List) {
                    return ((List<?>) cols).size();
                }
                // 标准格式：fields 在 schema 下
                Object schema = map.get("schema");
                if (schema instanceof Map) {
                    Object schemaFields = ((Map<?, ?>) schema).get("fields");
                    if (schemaFields instanceof List) {
                        return ((List<?>) schemaFields).size();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("countFields 解析失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 从 table Map 中提取 fields 列表。兼容两种输入格式：
     * <ol>
     *   <li>扁平格式（LLM 常用）：{@code fields:} 在 table 顶层</li>
     *   <li>标准格式：{@code schema: { fields: [...] }} 嵌套结构</li>
     * </ol>
     * <p>提取后从 tableMap 中移除对应键。对标准格式还会将 {@code schema.table}
     * 提升到 meta 的 {@code table} 字段。</p>
     *
     * @param tableMap table 的 YAML Map（会被修改）
     * @return fields 列表，不存在时返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractFieldsFromTable(Map<String, Object> tableMap) {
        // 扁平格式（LLM 输入常用）
        List<Map<String, Object>> fields = (List<Map<String, Object>>) tableMap.remove("fields");
        if (fields != null) {
            return fields;
        }
        // 标准格式（schema 嵌套）
        Map<String, Object> schemaMap = (Map<String, Object>) tableMap.remove("schema");
        if (schemaMap != null) {
            Object dbTable = schemaMap.get("table");
            if (dbTable != null) {
                tableMap.put("table", dbTable);
            }
            fields = (List<Map<String, Object>>) schemaMap.get("fields");
        }
        return fields != null ? fields : List.of();
    }

    // ==================== Table 操作 ====================

    /**
     * 完整追加一个 table（≤30 字段）。将 table YAML 拆分为 meta + fields 批次写入。
     *
     * @param rc        RuntimeContext
     * @param draftId   草稿 ID
     * @param tableYaml 完整 table YAML，需包含 name 字段
     * @return table 名称
     * @throws IllegalArgumentException table 已存在或 YAML 中缺少 name 字段
     */
    @SuppressWarnings("unchecked")
    public String appendTable(RuntimeContext rc, String draftId, String tableYaml) {
        Object loaded = yaml().load(tableYaml);
        // 兼容 LLM 常见错误：把单条 table 写成列表格式（- name: xxx），自动提取第一个元素
        if (loaded instanceof List) {
            List<?> list = (List<?>) loaded;
            if (!list.isEmpty() && list.get(0) instanceof Map) {
                log.debug("appendTable: 自动解包列表格式（LLM 误加前导 \"- \"），已提取第一个元素");
                loaded = list.get(0);
            }
        }
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException(
                    "tableYaml 格式错误：期望 Map 结构（如 name: xxx），实际收到 "
                            + (loaded instanceof List ? "列表（请去掉前导 \"- \"）" : loaded.getClass().getSimpleName()));
        }
        Map<String, Object> tableMap = (Map<String, Object>) loaded;
        String tableName = (String) tableMap.get("name");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table YAML 中缺少 name 字段");
        }

        // 检查重复
        String existingMeta = readTableFile(rc, draftId, tableName, META_FILE);
        if (existingMeta != null && !existingMeta.isBlank()) {
            throw new IllegalArgumentException(
                    "table「" + tableName + "」已存在，请用 updateTableInDraft 或 removeTableFromDraft");
        }

        // 拆分 meta 和 fields（兼容扁平格式和 schema 嵌套格式）
        Map<String, Object> meta = new LinkedHashMap<>(tableMap);
        List<Map<String, Object>> fields = extractFieldsFromTable(meta);

        // 写入 meta
        writeTableFile(rc, draftId, tableName, META_FILE, yaml().dump(meta));

        // 写入 fields（即使为空也写空列表）
        if (fields == null) {
            fields = List.of();
        }
        Map<String, Object> fieldsWrapper = new LinkedHashMap<>();
        fieldsWrapper.put("fields", fields);
        writeTableFile(rc, draftId, tableName, FIELDS_PREFIX + "01.yaml",
                yaml().dump(fieldsWrapper));

        log.info("已追加 table: draftId={}, tableName={}, fields={}", draftId, tableName,
                fields.size());
        addToTableIndex(rc, draftId, tableName);
        return tableName;
    }

    /**
     * 仅追加 table 元信息（不含 fields），用于大 table 分批场景。
     * 创建空的 fields_01.yaml，后续通过 appendFields 追加。
     *
     * @param rc       RuntimeContext
     * @param draftId  草稿 ID
     * @param metaYaml table 元信息 YAML，需包含 name 字段
     * @return table 名称
     * @throws IllegalArgumentException table 已存在或 YAML 中缺少 name 字段
     */
    @SuppressWarnings("unchecked")
    public String appendTableMeta(RuntimeContext rc, String draftId, String metaYaml) {
        Object loaded = yaml().load(metaYaml);
        // 兼容 LLM 常见错误：把单条 meta 写成列表格式（- name: xxx），自动提取第一个元素
        if (loaded instanceof List) {
            List<?> list = (List<?>) loaded;
            if (!list.isEmpty() && list.get(0) instanceof Map) {
                log.debug("appendTableMeta: 自动解包列表格式（LLM 误加前导 \"- \"），已提取第一个元素");
                loaded = list.get(0);
            }
        }
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException(
                    "metaYaml 格式错误：期望 Map 结构（如 name: xxx），实际收到 "
                            + (loaded instanceof List ? "列表（请去掉前导 \"- \"）" : loaded.getClass().getSimpleName()));
        }
        Map<String, Object> metaMap = (Map<String, Object>) loaded;
        String tableName = (String) metaMap.get("name");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("metaYaml 中缺少 name 字段");
        }

        String existingMeta = readTableFile(rc, draftId, tableName, META_FILE);
        if (existingMeta != null && !existingMeta.isBlank()) {
            throw new IllegalArgumentException(
                    "table「" + tableName + "」已存在，请用 updateTableInDraft 或 removeTableFromDraft");
        }

        Map<String, Object> clean = new LinkedHashMap<>(metaMap);
        clean.remove("fields");
        clean.remove("schema");

        writeTableFile(rc, draftId, tableName, META_FILE, yaml().dump(clean));
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("fields", List.of());
        writeTableFile(rc, draftId, tableName, FIELDS_PREFIX + "01.yaml", yaml().dump(empty));

        log.info("已创建 table 壳: draftId={}, tableName={}", draftId, tableName);
        addToTableIndex(rc, draftId, tableName);
        return tableName;
    }

    /**
     * 向已有 table 追加 fields 批次（第二批及以后）。
     *
     * @param rc          RuntimeContext
     * @param draftId     草稿 ID
     * @param tableName   table 名
     * @param fieldsYaml  fields 定义 YAML（可为 fields 列表或包含 fields 键的 Map）
     * @return 追加结果，包含批次号、本批字段数和累计字段数
     * @throws IllegalArgumentException table 不存在或 YAML 格式错误时抛出
     */
    @SuppressWarnings("unchecked")
    public AppendFieldsResult appendFields(RuntimeContext rc, String draftId, String tableName,
                              String fieldsYaml) {
        String meta = readTableFile(rc, draftId, tableName, META_FILE);
        if (meta == null || meta.isBlank()) {
            List<String> existing = listTableNames(rc, draftId);
            throw new IllegalArgumentException(
                    "table「" + tableName + "」不存在。可用 table: " + existing);
        }

        int maxBatch = findMaxFieldBatch(rc, draftId, tableName);
        int nextBatch = maxBatch + 1;
        if (nextBatch > MAX_FIELD_BATCHES) {
            throw new IllegalStateException(
                    "table「" + tableName + "」field 批次已达上限 " + MAX_FIELD_BATCHES + "，无法继续追加");
        }

        List<Map<String, Object>> fields = parseFieldsYaml(fieldsYaml);

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("fields", fields);

        String batchFile = String.format("%s%02d.yaml", FIELDS_PREFIX, nextBatch);
        writeTableFile(rc, draftId, tableName, batchFile, yaml().dump(wrapper));

        int cumulative = countTableFields(rc, draftId, tableName);

        log.info("已追加 fields: draftId={}, tableName={}, batch={}, count={}, cumulative={}",
                draftId, tableName, nextBatch, fields.size(), cumulative);
        return new AppendFieldsResult(nextBatch, fields.size(), cumulative);
    }

    /**
     * 查找指定 table 的最大 fields 批次编号。
     *
     * @param rc        RuntimeContext
     * @param draftId   草稿 ID
     * @param tableName table 名
     * @return 最大批次编号（无批次时返回 0）
     */
    private int findMaxFieldBatch(RuntimeContext rc, String draftId, String tableName) {
        int max = 0;
        for (int i = 1; i <= MAX_FIELD_BATCHES; i++) {
            String content = readTableFile(rc, draftId, tableName,
                    String.format("%s%02d.yaml", FIELDS_PREFIX, i));
            if (content != null && !content.isBlank()) {
                max = i;
            } else {
                break;
            }
        }
        return max;
    }

    /**
     * 解析 fields YAML 输入，兼容三种格式。
     *
     * <p>解析失败时会抛出明确异常，让 LLM 感知到并修正参数后重试，
     * 避免字段数据被静默丢弃导致最终 YAML 缺字段。</p>
     *
     * <ol>
     *   <li>含 {@code fields:} 键的 Map：提取内部列表</li>
     *   <li>裸列表（如 {@code - name: col1\n  type: string}）</li>
     *   <li>空/空白输入：返回空列表</li>
     * </ol>
     *
     * @throws IllegalArgumentException 非空 YAML 但格式不符合预期时抛出
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFieldsYaml(String fieldsYaml) {
        if (fieldsYaml == null || fieldsYaml.isBlank()) {
            return List.of();
        }
        Object parsed = yaml().load(fieldsYaml);
        if (parsed == null) {
            return List.of();
        }
        // 裸列表格式
        if (parsed instanceof List) {
            return (List<Map<String, Object>>) parsed;
        }
        // Map 格式
        if (parsed instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) parsed;
            if (map.containsKey("fields")) {
                Object cols = map.get("fields");
                if (cols instanceof List) {
                    return (List<Map<String, Object>>) cols;
                }
                throw new IllegalArgumentException(
                        "fields 键的值不是列表类型，实际类型: "
                                + (cols != null ? cols.getClass().getSimpleName() : "null")
                                + "。请检查 YAML 格式，确保 fields 下为列表项（每项以 \"- name:\" 开头）");
            }
            // Map 不含 fields 键 — 可能是单字段定义的 Map 被误传
            throw new IllegalArgumentException(
                    "YAML 解析为 Map 但缺少 'fields' 键。"
                            + "请用列表格式（每项以 \"- name: 字段名\" 开头），"
                            + "或包裹在 'fields:' 键下。当前解析到的键: " + map.keySet());
        }
        // 其他类型（String、Number 等）
        throw new IllegalArgumentException(
                "fields YAML 格式无法识别，解析结果类型: "
                        + parsed.getClass().getSimpleName()
                        + "。请确保 fields 为列表格式（每项以 \"- name:\" 开头）");
    }

    /**
     * 完整更新一个 table（替换 meta 和 fields）。
     *
     * @param rc        RuntimeContext
     * @param draftId   草稿 ID
     * @param tableName table 名
     * @param tableYaml 完整 table YAML
     * @throws IllegalArgumentException table 不存在时抛出
     */
    @SuppressWarnings("unchecked")
    public void updateTable(RuntimeContext rc, String draftId, String tableName,
                            String tableYaml) {
        String meta = readTableFile(rc, draftId, tableName, META_FILE);
        if (meta == null || meta.isBlank()) {
            List<String> existing = listTableNames(rc, draftId);
            throw new IllegalArgumentException(
                    "table「" + tableName + "」不存在，无法更新。可用 table: " + existing);
        }

        // 清空旧批次（保留第一批，后续批次全部清空）
        int maxBatch = findMaxFieldBatch(rc, draftId, tableName);
        for (int i = 2; i <= maxBatch; i++) {
            writeTableFile(rc, draftId, tableName,
                    String.format("%s%02d.yaml", FIELDS_PREFIX, i), "");
        }

        Map<String, Object> tableMap = yaml().load(tableYaml);
        Map<String, Object> newMeta = new LinkedHashMap<>(tableMap);
        List<Map<String, Object>> fields = extractFieldsFromTable(newMeta);

        writeTableFile(rc, draftId, tableName, META_FILE, yaml().dump(newMeta));

        Map<String, Object> fieldsWrapper = new LinkedHashMap<>();
        fieldsWrapper.put("fields", fields != null ? fields : List.of());
        writeTableFile(rc, draftId, tableName, FIELDS_PREFIX + "01.yaml",
                yaml().dump(fieldsWrapper));

        log.info("已更新 table: draftId={}, tableName={}", draftId, tableName);
    }

    /**
     * 从草稿中移除指定 table（清空相关文件）。
     *
     * @param rc        RuntimeContext
     * @param draftId   草稿 ID
     * @param tableName table 名
     * @throws IllegalArgumentException table 不存在时抛出
     */
    public void removeTable(RuntimeContext rc, String draftId, String tableName) {
        String meta = readTableFile(rc, draftId, tableName, META_FILE);
        if (meta == null || meta.isBlank()) {
            List<String> existing = listTableNames(rc, draftId);
            throw new IllegalArgumentException(
                    "table「" + tableName + "」不存在。可用 table: " + existing);
        }
        int maxBatch = findMaxFieldBatch(rc, draftId, tableName);
        for (int i = 1; i <= maxBatch; i++) {
            writeTableFile(rc, draftId, tableName,
                    String.format("%s%02d.yaml", FIELDS_PREFIX, i), "");
        }
        writeTableFile(rc, draftId, tableName, META_FILE, "");
        removeFromTableIndex(rc, draftId, tableName);
        log.info("已删除 table: draftId={}, tableName={}", draftId, tableName);
    }

    /**
     * 统计指定 table 当前累计字段数。
     *
     * @param rc        RuntimeContext
     * @param draftId   草稿 ID
     * @param tableName table 名
     * @return 累计字段数，table 不存在时返回 0
     */
    public int countTableFields(RuntimeContext rc, String draftId, String tableName) {
        Map<String, Object> table = mergeTable(rc, draftId, tableName);
        if (table == null) {
            return 0;
        }
        Map<?, ?> schema = (Map<?, ?>) table.get("schema");
        if (schema != null) {
            List<?> fields = (List<?>) schema.get("fields");
            return fields != null ? fields.size() : 0;
        }
        return 0;
    }

    /**
     * 预览单个 table 的当前合并结果（meta + 所有 columns 批次）。
     *
     * @param rc        RuntimeContext
     * @param draftId   草稿 ID
     * @param tableName table 名
     * @return 合并后的 table YAML 字符串，table 不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public String previewTable(RuntimeContext rc, String draftId, String tableName) {
        Map<String, Object> table = mergeTable(rc, draftId, tableName);
        if (table == null || table.isEmpty()) {
            return null;
        }
        return yaml().dump(table);
    }

    // ==================== Constraints / Seeds ====================

    /**
     * 设置草稿的约束定义。
     *
     * @param rc               RuntimeContext
     * @param draftId          草稿 ID
     * @param constraintsYaml  约束 YAML 内容，为空时写入空内容
     */
    public void setConstraints(RuntimeContext rc, String draftId, String constraintsYaml) {
        writeFile(rc, draftId, CONSTRAINTS_FILE,
                constraintsYaml != null ? constraintsYaml.trim() : "");
        log.info("已设置 constraints: draftId={}", draftId);
    }

    /**
     * 设置草稿的种子数据。
     *
     * @param rc         RuntimeContext
     * @param draftId    草稿 ID
     * @param seedsYaml  种子 YAML 内容，为空时写入空内容
     */
    public void setSeeds(RuntimeContext rc, String draftId, String seedsYaml) {
        writeFile(rc, draftId, SEEDS_FILE,
                seedsYaml != null ? seedsYaml.trim() : "");
        log.info("已设置 seeds: draftId={}", draftId);
    }

    // ==================== 合并 ====================

    /**
     * 将草稿中的所有部分合并为一份完整的 YAML 配置。
     *
     * <p>合并逻辑：</p>
     * <ol>
     *   <li>读取 header.yaml 作为根节点</li>
     *   <li>遍历所有 table 子目录，合并 meta 与 fields</li>
     *   <li>读取 constraints.yaml（可选）</li>
     *   <li>读取 seeds.yaml（可选）</li>
     * </ol>
     *
     * @param rc      RuntimeContext
     * @param draftId 草稿 ID
     * @return 合并后的完整 YAML 字符串
     * @throws IllegalStateException header 为空或不存在时抛出
     */
    @SuppressWarnings("unchecked")
    public String mergeToYaml(RuntimeContext rc, String draftId) {
        String headerContent = readFile(rc, draftId, HEADER_FILE);
        if (headerContent == null || headerContent.isBlank()) {
            throw new IllegalStateException(
                    "草稿「" + draftId + "」header.yaml 为空或不存在");
        }
        Map<String, Object> root = yaml().load(headerContent);

        // 合并 tables
        List<Map<String, Object>> tables = new ArrayList<>();
        List<String> tableNames = new ArrayList<>(listTableNames(rc, draftId));

        for (String tableName : tableNames) {
            Map<String, Object> table = mergeTable(rc, draftId, tableName);
            if (table != null && !table.isEmpty()) {
                tables.add(table);
            }
        }
        root.put("tables", tables);

        // 读取 constraints（可选，兼容列表和 Map 包装两种格式）
        String constraintsContent = readFile(rc, draftId, CONSTRAINTS_FILE);
        if (constraintsContent != null && !constraintsContent.isBlank()) {
            List<?> constraintsList = extractList(yaml().load(constraintsContent), "constraints");
            if (constraintsList != null && !constraintsList.isEmpty()) {
                root.put("constraints", constraintsList);
            }
        }

        // 读取 seeds（可选，兼容列表和 Map 包装两种格式）
        String seedsContent = readFile(rc, draftId, SEEDS_FILE);
        if (seedsContent != null && !seedsContent.isBlank()) {
            List<?> seedsList = extractList(yaml().load(seedsContent), "seeds");
            if (seedsList != null && !seedsList.isEmpty()) {
                root.put("seeds", seedsList);
            }
        }

        return yaml().dump(root);
    }

    /**
     * 合并单个 table 的 meta 与 fields 批次。
     *
     * @param rc        RuntimeContext
     * @param draftId   草稿 ID
     * @param tableName table 名
     * @return 合并后的 table Map，meta 为空时返回 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeTable(RuntimeContext rc, String draftId, String tableName) {
        String metaContent = readTableFile(rc, draftId, tableName, META_FILE);
        if (metaContent == null || metaContent.isBlank()) {
            return null;
        }
        Map<String, Object> table = yaml().load(metaContent);

        List<Map<String, Object>> allFields = new ArrayList<>();
        int batch = 1;
        while (batch <= MAX_FIELD_BATCHES) {
            String batchFile = String.format("%s%02d.yaml", FIELDS_PREFIX, batch);
            String batchContent = readTableFile(rc, draftId, tableName, batchFile);
            if (batchContent == null || batchContent.isBlank()) {
                break;
            }
            Map<String, Object> batchMap = yaml().load(batchContent);
            if (batchMap != null && batchMap.containsKey("fields")) {
                List<Map<String, Object>> cols =
                        (List<Map<String, Object>>) batchMap.get("fields");
                if (cols != null) {
                    allFields.addAll(cols);
                }
            }
            batch++;
        }
        // 构建 schema 块：将 fields 和 table（DB 表名）嵌套在 schema 下
        Map<String, Object> schemaMap = new LinkedHashMap<>();
        Object dbTable = table.remove("table");
        if (dbTable != null) {
            schemaMap.put("table", dbTable);
        }
        schemaMap.put("fields", allFields);
        table.put("schema", schemaMap);
        return table;
    }

    /**
     * 从 YAML 解析结果中提取列表。
     * <p>兼容两种格式：</p>
     * <ul>
     *   <li>直接为 List 时直接返回</li>
     *   <li>为 Map 时尝试按 key 提取内部 List（兼容旧草稿中 Map 包装的格式）</li>
     * </ul>
     *
     * @param parsed YAML 解析结果
     * @param key    Map 格式时的提取键名
     * @return 提取到的列表，无法提取时返回 null
     */
    @SuppressWarnings("unchecked")
    private List<?> extractList(Object parsed, String key) {
        if (parsed instanceof List) {
            return (List<?>) parsed;
        }
        if (parsed instanceof Map) {
            Object inner = ((Map<String, Object>) parsed).get(key);
            if (inner instanceof List) {
                return (List<?>) inner;
            }
        }
        return null;
    }

    /**
     * 列出草稿中的所有 table 名称。
     *
     * @param rc      RuntimeContext
     * @param draftId 草稿 ID
     * @return table 名称列表（已排序）
     */
    public List<String> listTableNames(RuntimeContext rc, String draftId) {
        String content = readFile(rc, draftId, TABLES_INDEX);
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return Arrays.stream(content.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .sorted()
                .toList();
    }

    /** 向 table 索引文件追加一行 table 名 */
    private void addToTableIndex(RuntimeContext rc, String draftId, String tableName) {
        String current = readFile(rc, draftId, TABLES_INDEX);
        String updated = (current != null && !current.isBlank() ? current + "\n" : "") + tableName;
        writeFile(rc, draftId, TABLES_INDEX, updated);
    }

    /** 从 table 索引文件中移除指定 table 名 */
    private void removeFromTableIndex(RuntimeContext rc, String draftId, String tableName) {
        String current = readFile(rc, draftId, TABLES_INDEX);
        if (current == null || current.isBlank()) {
            return;
        }
        String updated = Arrays.stream(current.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equals(tableName))
                .collect(java.util.stream.Collectors.joining("\n"));
        writeFile(rc, draftId, TABLES_INDEX, updated);
    }

    // ==================== 编辑模式初始化 ====================

    /**
     * 将已有完整配置 YAML 拆分为草稿结构，用于编辑模式。
     *
     * <p>拆分逻辑：</p>
     * <ol>
     *   <li>顶级字段（除 tables/constraints/seeds）写入 header.yaml</li>
     *   <li>每个 table 拆为 meta + fields 写入 table_&lt;name&gt;/ 子目录</li>
     *   <li>constraints 写入 constraints.yaml</li>
     *   <li>seeds 写入 seeds.yaml</li>
     * </ol>
     *
     * @param rc       RuntimeContext
     * @param draftId  草稿 ID
     * @param fullYaml 完整配置 YAML
     */
    @SuppressWarnings("unchecked")
    public void loadExistingAsDraft(RuntimeContext rc, String draftId, String fullYaml) {
        // 冲突检测：草稿已存在时先清理旧数据，避免静默覆盖导致表/字段丢失
        if (draftExists(rc, draftId)) {
            log.warn("草稿「{}」已存在，覆盖前先清理旧数据", draftId);
            deleteDraft(rc, draftId);
        }

        Map<String, Object> root = yaml().load(fullYaml);

        // 1. 提取 header（除 tables/constraints/seeds 外的顶级字段）
        Map<String, Object> header = new LinkedHashMap<>(root);
        List<Map<String, Object>> tables =
                (List<Map<String, Object>>) header.remove("tables");
        header.remove("constraints");
        header.remove("seeds");
        writeFile(rc, draftId, HEADER_FILE, yaml().dump(header));

        // 2. 拆分 tables，同时构建索引
        List<String> indexNames = new ArrayList<>();
        if (tables != null) {
            for (Map<String, Object> table : tables) {
                String tableName = (String) table.get("name");
                if (tableName == null || tableName.isBlank()) {
                    continue;
                }

                Map<String, Object> meta = new LinkedHashMap<>(table);
                List<Map<String, Object>> fields = extractFieldsFromTable(meta);

                writeTableFile(rc, draftId, tableName, META_FILE, yaml().dump(meta));

                Map<String, Object> colsWrapper = new LinkedHashMap<>();
                colsWrapper.put("fields", fields != null ? fields : List.of());
                writeTableFile(rc, draftId, tableName, FIELDS_PREFIX + "01.yaml",
                        yaml().dump(colsWrapper));
                indexNames.add(tableName);
            }
        }

        // 写入 table 索引文件
        if (!indexNames.isEmpty()) {
            writeFile(rc, draftId, TABLES_INDEX, String.join("\n", indexNames));
        }

        // 3. 拆分 constraints（兼容 List 和 Map 两种格式）
        Object constraints = root.get("constraints");
        if (constraints instanceof List && !((List<?>) constraints).isEmpty()) {
            writeFile(rc, draftId, CONSTRAINTS_FILE, yaml().dump(constraints));
        } else if (constraints instanceof Map && !((Map<?, ?>) constraints).isEmpty()) {
            writeFile(rc, draftId, CONSTRAINTS_FILE, yaml().dump(constraints));
        }

        // 4. 拆分 seeds（兼容 List 和 Map 两种格式）
        Object seeds = root.get("seeds");
        if (seeds instanceof List && !((List<?>) seeds).isEmpty()) {
            writeFile(rc, draftId, SEEDS_FILE, yaml().dump(seeds));
        } else if (seeds instanceof Map && !((Map<?, ?>) seeds).isEmpty()) {
            writeFile(rc, draftId, SEEDS_FILE, yaml().dump(seeds));
        }

        log.info("已加载已有配置为草稿: draftId={}, tables={}",
                draftId, tables != null ? tables.size() : 0);
    }
}
