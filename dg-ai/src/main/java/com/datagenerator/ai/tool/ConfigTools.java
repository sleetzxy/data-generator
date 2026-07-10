package com.datagenerator.ai.tool;

import com.datagenerator.ai.client.DgWebClient;
import com.datagenerator.ai.client.DgWebClient.ConfigDetail;
import com.datagenerator.ai.client.DgWebClient.ConfigSummary;
import com.datagenerator.ai.client.DgWebClient.ValidationResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 配置管理工具集 — 供 HarnessAgent ReAct 循环调用。
 *
 * <p>所有配置创建和编辑统一使用草稿模式：startConfigDraft / startEditDraft → 分段添加 → saveConfigDraft。</p>
 */
@Component
public class ConfigTools {

    private static final Logger log = LoggerFactory.getLogger(ConfigTools.class);

    private static final Pattern YAML_FENCE = Pattern.compile(
            "```yaml\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);
    private static final Pattern ID_PATTERN = Pattern.compile(
            "^id:\\s*\"?([a-zA-Z0-9_-]+)\"?\\s*$", Pattern.MULTILINE);
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "^name:\\s*\"?([^\"]+?)\"?\\s*$", Pattern.MULTILINE);

    private static final int SMALL_TABLE_FIELD_HINT = 30;

    private final DgWebClient client;
    private final ObjectProvider<HarnessAgent> harnessAgentProvider;
    private final ConfigDraftManager draftManager;

    public ConfigTools(Optional<DgWebClient> client, ObjectProvider<HarnessAgent> harnessAgentProvider,
            ConfigDraftManager draftManager) {
        this.client = client.orElse(null);
        this.harnessAgentProvider = harnessAgentProvider;
        this.draftManager = draftManager;
    }

    private boolean hasClient() {
        return client != null;
    }

    /**
     * 通过 ToolEmitter 发送工具结果，触发 TOOL_RESULT_TEXT_DELTA 事件，
     * 使前端实时对话中能展示工具调用结果。
     */
    private String emitAndReturn(ToolEmitter emitter, String result) {
        if (emitter != null) {
            emitter.emit(ToolResultBlock.text(result));
        }
        return result;
    }

    // ==================== CRUD ====================

    @Tool(name = "listConfigs", description = "列出所有已有 Job 配置，返回显示名称和文件名")
    public String listConfigs(ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法查询配置");
        }
        List<ConfigSummary> list = client.listConfigs();
        if (list.isEmpty()) {
            return emitAndReturn(emitter, "暂无已有配置");
        }
        StringBuilder sb = new StringBuilder("已有配置：\n");
        for (ConfigSummary s : list) {
            sb.append("- ").append(s.name())
                    .append("（文件: ").append(s.fileName()).append("）\n");
        }
        return emitAndReturn(emitter, sb.toString());
    }

    @Tool(name = "getConfig", description = "查看指定配置的完整 YAML 内容。编辑已有配置时请先调用此工具获取内容")
    public String getConfig(@ToolParam(name = "fileName", description = "配置文件名（不含扩展名）") String fileName,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法查询配置");
        }
        ConfigDetail d = client.getConfig(fileName);
        if (d == null) {
            return emitAndReturn(emitter, "未找到配置：" + fileName);
        }
        return emitAndReturn(emitter, "配置「" + d.name() + "」\n"
                + "文件名: " + d.fileName() + "\n"
                + "```yaml\n" + d.yaml() + "\n```\n"
                + "（编辑已有配置请使用 startEditDraft(\""
                + d.fileName() + "\"）");
    }

    @Tool(name = "deleteConfig", description = "删除指定配置。删除前需向用户确认")
    public String deleteConfig(@ToolParam(name = "fileName", description = "配置文件名（不含扩展名）") String fileName,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法删除配置");
        }
        ConfigDetail d = client.getConfig(fileName);
        if (d == null) {
            return emitAndReturn(emitter, "未找到配置：" + fileName);
        }
        client.deleteConfig(fileName);
        return emitAndReturn(emitter, "已删除配置：「" + d.name() + "」(" + fileName + ")");
    }

    // ==================== 环境查询 ====================

    @Tool(name = "listConnections", description = "查询所有可用数据库连接")
    public String listConnections(ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "当前无可用的数据连接（未连接数据服务）");
        }
        var conns = client.listConnections();
        if (conns == null || conns.isEmpty()) {
            return emitAndReturn(emitter, "当前无可用数据连接");
        }
        StringBuilder sb = new StringBuilder("可用连接：\n");
        conns.forEach(c -> sb.append("- ").append(c.name()).append(" (").append(c.type()).append(")\n"));
        return emitAndReturn(emitter, sb.toString());
    }

    @Tool(name = "listSchemas", description = "查询所有可用 Schema 定义及其字段详情")
    public String listSchemas(ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "当前无可用的 Schema（未连接数据服务）");
        }
        var names = client.listSchemas();
        if (names == null || names.isEmpty()) {
            return emitAndReturn(emitter, "当前无可用 Schema");
        }
        StringBuilder sb = new StringBuilder("可用 Schema：\n");
        for (String name : names) {
            var d = client.getSchema(name);
            if (d != null && d.fields() != null) {
                sb.append("- ").append(name).append(" (").append(d.fields().size()).append(" 字段): ");
                d.fields().forEach(f -> sb.append(f.name()).append("(").append(f.type()).append(") "));
                sb.append("\n");
            }
        }
        return emitAndReturn(emitter, sb.toString());
    }

    @Tool(name = "getSchema", description = "查看某个 Schema 的字段详情")
    public String getSchema(@ToolParam(name = "name", description = "Schema 名称") String name,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法查询 Schema");
        }
        var d = client.getSchema(name);
        if (d == null) {
            return emitAndReturn(emitter, "未找到 Schema：" + name);
        }
        StringBuilder sb = new StringBuilder("Schema「").append(name).append("」字段（共 ")
                .append(d.fields().size()).append(" 个）：\n");
        d.fields().forEach(f -> sb.append("- ").append(f.name())
                .append(" (").append(f.type()).append(")\n"));
        if (d.fields().size() > SMALL_TABLE_FIELD_HINT) {
            sb.append("\n字段较多：请用 startConfigDraft 创建草稿，再分批添加字段。");
        }
        return emitAndReturn(emitter, sb.toString());
    }

    // ==================== 草稿生命周期 ====================

    @Tool(name = "startConfigDraft", description = "新建配置草稿。传入草稿标识和 header YAML（id、name、writer 等顶级字段，不含 tables）")
    public String startConfigDraft(
            @ToolParam(name = "draftId", description = "草稿标识，建议使用配置的文件名") String draftId,
            @ToolParam(name = "headerYaml", description = "配置 header YAML（id/name/writer/writers 等，不含 tables）") String headerYaml,
            ToolEmitter emitter, RuntimeContext rc) {
        if (draftId == null || draftId.isBlank()) {
            return emitAndReturn(emitter, "错误：draftId 不能为空");
        }
        try {
            draftManager.createDraft(rc, draftId, headerYaml.trim());
            return emitAndReturn(emitter, "草稿「" + draftId + "」已创建。接下来请用 addTableToDraft 添加表定义。");
        } catch (Exception e) {
            log.error("创建草稿失败: draftId={}", draftId, e);
            return emitAndReturn(emitter, "创建草稿失败：" + e.getMessage());
        }
    }

    @Tool(name = "startEditDraft", description = "加载已有配置为草稿以便编辑。configName 为配置文件名（不含扩展名）")
    public String startEditDraft(
            @ToolParam(name = "configName", description = "已有配置的文件名（不含 .yml 扩展名）") String configName,
            ToolEmitter emitter, RuntimeContext rc) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法加载配置");
        }
        ConfigDetail detail = client.getConfig(configName);
        if (detail == null) {
            return emitAndReturn(emitter, "未找到配置：" + configName);
        }
        try {
            draftManager.loadExistingAsDraft(rc, configName, detail.yaml());
            List<String> tableNames = draftManager.listTableNames(rc, configName);

            // 列出各 table 及列数，帮助 LLM 了解编辑规模
            StringBuilder tablesInfo = new StringBuilder();
            for (String tn : tableNames) {
                int colCount = draftManager.countTableFields(rc, configName, tn);
                tablesInfo.append("\n  - ").append(tn).append("（").append(colCount).append(" 字段）");
            }

            return emitAndReturn(emitter, "已加载配置「" + detail.name() + "」为草稿「" + configName + "」\n"
                    + "共 " + tableNames.size() + " 个 table:" + tablesInfo + "\n"
                    + "接下来可修改各部分，完成后 saveConfigDraft 保存。");
        } catch (Exception e) {
            log.error("加载配置为草稿失败: configName={}", configName, e);
            return emitAndReturn(emitter, "加载配置失败：" + e.getMessage());
        }
    }

    @Tool(name = "saveConfigDraft", description = "合并草稿所有分段文件为完整 YAML，校验后保存到 dg-web。自动判断新建或更新")
    public String saveConfigDraft(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            ToolEmitter emitter, RuntimeContext rc) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法保存配置");
        }
        try {
            // 轻量级预检：草稿是否存在、是否有 table（避免昂贵的合并+校验在空草稿上浪费）
            if (!draftManager.draftExists(rc, draftId)) {
                return emitAndReturn(emitter, "草稿「" + draftId + "」不存在或已被废弃，请检查 draftId。");
            }
            List<String> tableNames = draftManager.listTableNames(rc, draftId);
            if (tableNames.isEmpty()) {
                return emitAndReturn(emitter, "草稿「" + draftId + "」尚未包含任何 table 定义，请先用 addTableToDraft 或 addTableMetaToDraft 添加表。");
            }

            // 检查是否有 table 的字段数为 0（可能是创建了壳但未追加字段）
            StringBuilder emptyCols = new StringBuilder();
            for (String tn : tableNames) {
                int colCount = draftManager.countTableFields(rc, draftId, tn);
                if (colCount == 0) {
                    emptyCols.append("\n  - ").append(tn).append("（0 字段，请用 addFieldsToTable 追加字段或 removeTableFromDraft 删除）");
                }
            }
            if (!emptyCols.isEmpty()) {
                return emitAndReturn(emitter, "以下 table 尚未定义任何字段，无法保存：" + emptyCols
                        + "\n请先补充字段定义或删除这些空 table，再保存。");
            }

            String merged = draftManager.mergeToYaml(rc, draftId);
            String fileName = extractConfigId(merged);
            String configName = extractConfigName(merged);
            if (configName == null || configName.isBlank()) {
                configName = fileName;
            }

            ValidationResult vr = client.validateYaml(merged);
            if (!vr.valid()) {
                return emitAndReturn(emitter, "校验失败，未保存。草稿保留在 workspace 中可继续修改：\n"
                        + String.join("\n", vr.errors()));
            }

            ConfigDetail existing = client.getConfig(fileName);
            ConfigDetail saved;
            boolean isUpdate = existing != null;
            if (isUpdate) {
                // 编辑模式：服务端 get() 会通过 stripNameFromContent 剥离 YAML 中的 name 字段，
                // 导致 extractConfigName 返回 null 并兜底为 id。此处用已有配置的原始显示名修正。
                if (configName == null || configName.isBlank() || configName.equals(fileName)) {
                    configName = existing.name();
                }
                saved = client.updateConfig(fileName, configName, merged);
            } else {
                saved = client.createConfig(configName, merged);
            }
            if (saved == null) {
                return emitAndReturn(emitter, "保存失败：服务端返回空结果，请检查 dg-web 连接状态");
            }
            String action = isUpdate ? "已更新" : "已保存";
            return emitAndReturn(emitter, "配置" + action + " ✅\n- 文件名: " + saved.fileName() + "\n- 显示名: " + saved.name());
        } catch (Exception e) {
            log.error("保存草稿失败: draftId={}", draftId, e);
            return emitAndReturn(emitter, "保存失败：" + e.getMessage());
        }
    }

    @Tool(name = "discardConfigDraft", description = "废弃草稿，删除草稿目录")
    public String discardConfigDraft(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            draftManager.deleteDraft(rc, draftId);
            return emitAndReturn(emitter, "草稿「" + draftId + "」已废弃。");
        } catch (Exception e) {
            log.error("废弃草稿失败: draftId={}", draftId, e);
            return emitAndReturn(emitter, "废弃草稿失败：" + e.getMessage());
        }
    }

    // ==================== Table 级别 ====================

    @Tool(name = "addTableToDraft", description = "向草稿追加完整 table 定义（含 fields）。仅适用于 ≤30 字段的 table，超过请用 addTableMetaToDraft + addFieldsToTable 分批")
    public String addTableToDraft(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            @ToolParam(name = "tableYaml", description = "完整 table YAML（含 name、count、fields 等）") String tableYaml,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            String cleaned = tableYaml.trim();
            // 预检字段数：超过阈值时给出提示但仍允许写入（LLM 可能已在分批）
            int fieldCount = draftManager.countFields(cleaned);
            String tableName = draftManager.appendTable(rc, draftId, cleaned);
            String extra = "";
            if (fieldCount > 0) {
                extra = "（" + fieldCount + " 字段）";
            }
            if (fieldCount > SMALL_TABLE_FIELD_HINT) {
                extra += " ⚠️ 字段较多，建议后续用 addTableMetaToDraft + addFieldsToTable 分批处理大表";
            }
            return emitAndReturn(emitter, "table「" + tableName + "」" + extra + " 已添加到草稿「" + draftId + "」。");
        } catch (Exception e) {
            log.error("添加 table 失败: draftId={}", draftId, e);
            return emitAndReturn(emitter, "添加 table 失败：" + e.getMessage());
        }
    }

    @Tool(name = "updateTableInDraft", description = "替换草稿中指定 table 的全部内容")
    public String updateTableInDraft(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            @ToolParam(name = "tableName", description = "要替换的 table 名称") String tableName,
            @ToolParam(name = "tableYaml", description = "新的完整 table YAML") String tableYaml,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            draftManager.updateTable(rc, draftId, tableName, tableYaml.trim());
            return emitAndReturn(emitter, "table「" + tableName + "」已更新。");
        } catch (Exception e) {
            log.error("更新 table 失败: draftId={}, tableName={}", draftId, tableName, e);
            return emitAndReturn(emitter, "更新 table 失败：" + e.getMessage());
        }
    }

    @Tool(name = "removeTableFromDraft", description = "从草稿中删除指定 table")
    public String removeTableFromDraft(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            @ToolParam(name = "tableName", description = "要删除的 table 名称") String tableName,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            draftManager.removeTable(rc, draftId, tableName);
            return emitAndReturn(emitter, "table「" + tableName + "」已从草稿「" + draftId + "」中删除。");
        } catch (Exception e) {
            log.error("删除 table 失败: draftId={}, tableName={}", draftId, tableName, e);
            return emitAndReturn(emitter, "删除 table 失败：" + e.getMessage());
        }
    }

    // ==================== Fields 级别 ====================

    @Tool(name = "addTableMetaToDraft", description = "向草稿添加 table 壳子（不含 fields），返回 table 名称。适用于大 table（>30 字段），后续用 addFieldsToTable 分批追加 fields")
    public String addTableMetaToDraft(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            @ToolParam(name = "metaYaml", description = "table 元信息 YAML（name、count、connection 等，不含 fields）") String metaYaml,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            String tableName = draftManager.appendTableMeta(rc, draftId, metaYaml.trim());
            return emitAndReturn(emitter, "table 壳「" + tableName + "」已创建（0 字段）。请用 addFieldsToTable(draftId=\""
                    + draftId + "\", tableName=\"" + tableName + "\", fieldsYaml=...) 逐批追加字段。");
        } catch (Exception e) {
            log.error("添加 table meta 失败: draftId={}", draftId, e);
            return emitAndReturn(emitter, "添加 table meta 失败：" + e.getMessage());
        }
    }

    @Tool(name = "addFieldsToTable", description = "向草稿中指定 table 追加一批 fields。自动追加到下一批次文件")
    public String addFieldsToTable(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            @ToolParam(name = "tableName", description = "目标 table 名称（必须已通过 addTableMetaToDraft 或 addTableToDraft 创建）") String tableName,
            @ToolParam(name = "fieldsYaml", description = "fields YAML 片段（可只写 fields 列表，也可带外层 fields: 键）") String fieldsYaml,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            ConfigDraftManager.AppendFieldsResult r =
                    draftManager.appendFields(rc, draftId, tableName, fieldsYaml.trim());
            return emitAndReturn(emitter, "fields 已追加到 table「" + tableName
                    + "」（第 " + r.batchNum() + " 批，+ " + r.batchCount()
                    + " 字段，累计 " + r.cumulativeCount() + " 字段）");
        } catch (Exception e) {
            log.error("追加 fields 失败: draftId={}, tableName={}", draftId, tableName, e);
            return emitAndReturn(emitter, "追加 fields 失败：" + e.getMessage());
        }
    }

    // ==================== Section 级别 ====================

    @Tool(name = "setDraftConstraints", description = "设置草稿的 constraints（覆盖已有）")
    public String setDraftConstraints(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            @ToolParam(name = "constraintsYaml", description = "constraints YAML 内容") String constraintsYaml,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            draftManager.setConstraints(rc, draftId, constraintsYaml);
            return emitAndReturn(emitter, "constraints 已设置。");
        } catch (Exception e) {
            log.error("设置 constraints 失败: draftId={}", draftId, e);
            return emitAndReturn(emitter, "设置 constraints 失败：" + e.getMessage());
        }
    }

    @Tool(name = "setDraftSeeds", description = "设置草稿的 seeds（覆盖已有）")
    public String setDraftSeeds(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            @ToolParam(name = "seedsYaml", description = "seeds YAML 内容") String seedsYaml,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            draftManager.setSeeds(rc, draftId, seedsYaml);
            return emitAndReturn(emitter, "seeds 已设置。");
        } catch (Exception e) {
            log.error("设置 seeds 失败: draftId={}", draftId, e);
            return emitAndReturn(emitter, "设置 seeds 失败：" + e.getMessage());
        }
    }

    @Tool(name = "previewTableInDraft", description = "预览草稿中单个 table 的当前合并状态（meta + 所有 fields 批次），用于分批追加 fields 时验证格式和进度")
    public String previewTableInDraft(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            @ToolParam(name = "tableName", description = "table 名称") String tableName,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            String yaml = draftManager.previewTable(rc, draftId, tableName);
            if (yaml == null) {
                return emitAndReturn(emitter, "table「" + tableName + "」在草稿「" + draftId + "」中不存在。"
                        + "可用 table: " + draftManager.listTableNames(rc, draftId));
            }
            int colCount = draftManager.countTableFields(rc, draftId, tableName);
            return emitAndReturn(emitter, "table「" + tableName + "」（" + colCount + " 字段）当前内容：\n```yaml\n" + yaml + "\n```");
        } catch (Exception e) {
            log.error("预览 table 失败: draftId={}, tableName={}", draftId, tableName, e);
            return emitAndReturn(emitter, "预览 table 失败：" + e.getMessage());
        }
    }

    @Tool(name = "previewConfigDraft", description = "预览草稿合并后的完整 YAML。不校验不保存，仅供检查")
    public String previewConfigDraft(
            @ToolParam(name = "draftId", description = "草稿标识") String draftId,
            ToolEmitter emitter, RuntimeContext rc) {
        try {
            String merged = draftManager.mergeToYaml(rc, draftId);
            return emitAndReturn(emitter, "草稿「" + draftId + "」当前配置：\n```yaml\n" + merged + "\n```");
        } catch (Exception e) {
            log.error("预览草稿失败: draftId={}", draftId, e);
            return emitAndReturn(emitter, "预览失败：" + e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    static String cleanYamlMarkdown(String yaml) {
        if (yaml == null) {
            return "";
        }
        Matcher m = YAML_FENCE.matcher(yaml);
        if (m.find()) {
            return m.group(1).trim();
        }
        return yaml.trim();
    }

    private static String extractConfigId(String yaml) {
        Matcher m = ID_PATTERN.matcher(yaml);
        if (m.find()) {
            return m.group(1);
        }
        return "generated-" + System.currentTimeMillis();
    }

    private static String extractConfigName(String yaml) {
        Matcher m = NAME_PATTERN.matcher(yaml);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }
}
