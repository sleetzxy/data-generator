package com.datagenerator.ai.tool;

import com.datagenerator.ai.client.DgWebClient;
import com.datagenerator.ai.client.DgWebClient.ConfigDetail;
import com.datagenerator.ai.client.DgWebClient.ConfigSummary;
import com.datagenerator.ai.client.DgWebClient.PreviewContentResult;
import com.datagenerator.ai.client.DgWebClient.PreviewTableSummary;
import com.datagenerator.ai.client.DgWebClient.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 配置管理工具集 — 直接对接 dg-web 接口；大 YAML 可选写入 workspace 后一次提交。
 */
@Component
public class ConfigTools {

    private static final Logger log = LoggerFactory.getLogger(ConfigTools.class);

    private static final Pattern YAML_FENCE = Pattern.compile(
            "```yaml\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);

    /** workspace 中 YAML 暂存目录前缀 */
    private static final String YAML_DIR = "yaml";

    private final DgWebClient client;
    private final ObjectProvider<HarnessAgent> harnessAgentProvider;
    private final ObjectMapper objectMapper;

    private volatile WorkspaceManager cachedWorkspaceManager;

    public ConfigTools(
            Optional<DgWebClient> client,
            ObjectProvider<HarnessAgent> harnessAgentProvider,
            ObjectMapper objectMapper) {
        this.client = client.orElse(null);
        this.harnessAgentProvider = harnessAgentProvider;
        this.objectMapper = objectMapper;
    }

    private boolean hasClient() {
        return client != null;
    }

    private String emitAndReturn(ToolEmitter emitter, String result) {
        if (emitter != null) {
            emitter.emit(ToolResultBlock.text(result));
        }
        return result;
    }

    /** 返回 ToolResultBlock，同时经 emitter 推送，保证前端 observation 能收到 delta/result */
    private ToolResultBlock emitAndReturnBlock(ToolEmitter emitter, String text) {
        ToolResultBlock block = ToolResultBlock.text(text);
        if (emitter != null) {
            emitter.emit(block);
        }
        return block;
    }

    // ==================== CRUD ====================

    @Tool(name = "listConfigs", description = "列出所有已有任务配置，返回显示名称和文件名")
    public String listConfigs(ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法查询配置");
        }
        List<ConfigSummary> list = client.listConfigs();
        if (list == null || list.isEmpty()) {
            return emitAndReturn(emitter, "暂无已有配置");
        }
        StringBuilder sb = new StringBuilder("已有配置：\n");
        for (ConfigSummary s : list) {
            sb.append("- ").append(s.name())
                    .append("（文件: ").append(s.fileName()).append("）\n");
        }
        return emitAndReturn(emitter, sb.toString());
    }

    @Tool(name = "getConfig", description = "查看指定配置的完整 YAML。编辑时先取回内容，改完后用 saveConfig 或 submitWorkspaceYaml 提交")
    public String getConfig(
            @ToolParam(name = "fileName", description = "配置文件名（不含扩展名）") String fileName,
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
                + "```yaml\n" + d.yaml() + "\n```");
    }

    @Tool(name = "deleteConfig", description = "删除指定配置。删除前需向用户确认")
    public String deleteConfig(
            @ToolParam(name = "fileName", description = "配置文件名（不含扩展名）") String fileName,
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

    @Tool(name = "listTableSchemas", description = "查询所有可用表结构定义及其字段详情")
    public String listTableSchemas(ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "当前无可用的表结构定义（未连接数据服务）");
        }
        var names = client.listTableSchemas();
        if (names == null || names.isEmpty()) {
            return emitAndReturn(emitter, "当前无可用表结构定义");
        }
        StringBuilder sb = new StringBuilder("可用表结构定义：\n");
        for (String name : names) {
            var d = client.getTableSchema(name);
            if (d != null && d.fields() != null) {
                sb.append("- ").append(name).append(" (").append(d.fields().size()).append(" 字段): ");
                d.fields().forEach(f -> sb.append(f.name()).append("(").append(f.type()).append(") "));
                sb.append("\n");
            }
        }
        return emitAndReturn(emitter, sb.toString());
    }

    @Tool(name = "getTableSchema", description = "查看某个表结构定义的字段详情")
    public String getTableSchema(
            @ToolParam(name = "name", description = "表结构定义名称") String name,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法查询表结构定义");
        }
        var d = client.getTableSchema(name);
        if (d == null) {
            return emitAndReturn(emitter, "未找到表结构定义：" + name);
        }
        StringBuilder sb = new StringBuilder("表结构定义「").append(name).append("」字段（共 ")
                .append(d.fields().size()).append(" 个）：\n");
        d.fields().forEach(f -> sb.append("- ").append(f.name())
                .append(" (").append(f.type()).append(")\n"));
        if (d.fields().size() > 30) {
            sb.append("\n字段较多：可分批 writeWorkspaceYaml / appendWorkspaceYaml，再用 submitWorkspaceYaml 提交。");
        }
        return emitAndReturn(emitter, sb.toString());
    }

    // ==================== 直连 YAML 接口 ====================

    @Tool(name = "validateConfigYaml", description = "校验完整任务 YAML（不落盘）。通过后再 preview / save")
    public String validateConfigYaml(
            @ToolParam(name = "yaml", description = "完整任务配置 YAML") String yaml,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法校验");
        }
        String cleaned = cleanYamlMarkdown(yaml);
        if (cleaned.isBlank()) {
            return emitAndReturn(emitter, "错误：yaml 不能为空");
        }
        ValidationResult vr = client.validateYaml(cleaned);
        if (vr.valid()) {
            return emitAndReturn(emitter, "校验通过");
        }
        return emitAndReturn(emitter, "校验失败：\n" + String.join("\n", vr.errors()));
    }

    /**
     * 预览样例数据。成功时返回约定 JSON：
     * {@code { "ui":"table", "title":"样例预览", "message":"...", "tables":[...] }}，
     * 前端按 {@code ui} 通用地渲染，不依赖工具名。
     */
    @Tool(name = "previewData", description = "预览已保存任务生成的样例数据（不是预览配置文本）。须先 saveConfig / submitWorkspaceYaml；传 fileName。"
            + "成功返回 JSON：ui=table、title、message、tables；你只需用业务语言说明")
    public ToolResultBlock previewData(
            @ToolParam(name = "fileName", description = "已保存配置的文件名（不含扩展名），即 saveConfig 返回的文件名") String fileName,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturnBlock(emitter, "未连接数据服务，无法预览数据");
        }
        if (fileName == null || fileName.isBlank()) {
            return emitAndReturnBlock(emitter, "错误：fileName 不能为空；请先保存任务再预览数据");
        }
        try {
            PreviewContentResult result = client.previewByFileName(fileName.trim());
            if (!result.ok()) {
                String msg = result.message() == null || result.message().isBlank()
                        ? "预览数据失败"
                        : result.message();
                return emitAndReturnBlock(emitter, msg);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ui", "table");
            payload.put("title", "样例预览");
            payload.put("message", formatPreviewSummary(result.tables()));
            payload.put("tables", toWireTables(result.tables()));
            return emitAndReturnBlock(emitter, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("previewData 失败: fileName={}", fileName, e);
            return emitAndReturnBlock(emitter, "预览数据失败：" + e.getMessage());
        }
    }

    @Tool(name = "saveConfig", description = "校验并保存完整 YAML。新建须 displayName；更新已有配置时传 fileName，displayName 可省略沿用原名")
    public String saveConfig(
            @ToolParam(name = "yaml", description = "完整任务配置 YAML") String yaml,
            @ToolParam(name = "displayName", description = "任务显示名称（新建必填；更新可省略）") String displayName,
            @ToolParam(name = "fileName", description = "配置文件名不含扩展名；更新必填；新建可选（不传则由服务端生成）") String fileName,
            ToolEmitter emitter) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法保存配置");
        }
        String cleaned = cleanYamlMarkdown(yaml);
        if (cleaned.isBlank()) {
            return emitAndReturn(emitter, "错误：yaml 不能为空");
        }
        return emitAndReturn(emitter, persistYaml(cleaned, displayName, fileName));
    }

    // ==================== Workspace 薄层（大 YAML） ====================

    @Tool(name = "writeWorkspaceYaml", description = "覆盖写入 workspace 中的 YAML 文件（大配置可分片：先 write 再多次 append）。path 为相对路径，如 my-job.yaml")
    public String writeWorkspaceYaml(
            @ToolParam(name = "path", description = "相对路径，如 my-job.yaml（自动落在 yaml/ 下）") String path,
            @ToolParam(name = "content", description = "写入内容（覆盖）") String content,
            ToolEmitter emitter,
            RuntimeContext rc) {
        try {
            String rel = resolveYamlPath(path);
            writeWorkspace(rc, rel, content == null ? "" : content);
            return emitAndReturn(emitter, "已写入 workspace：" + rel + "（" + (content == null ? 0 : content.length()) + " 字符）");
        } catch (Exception e) {
            log.error("writeWorkspaceYaml 失败: path={}", path, e);
            return emitAndReturn(emitter, "写入失败：" + e.getMessage());
        }
    }

    @Tool(name = "appendWorkspaceYaml", description = "向 workspace 中已有 YAML 文件追加内容（适合大表分批写 fields）")
    public String appendWorkspaceYaml(
            @ToolParam(name = "path", description = "相对路径，如 my-job.yaml") String path,
            @ToolParam(name = "content", description = "追加内容") String content,
            ToolEmitter emitter,
            RuntimeContext rc) {
        try {
            String rel = resolveYamlPath(path);
            String existing = readWorkspace(rc, rel);
            String chunk = content == null ? "" : content;
            String merged = existing == null ? chunk : existing + chunk;
            writeWorkspace(rc, rel, merged);
            return emitAndReturn(emitter, "已追加到 workspace：" + rel
                    + "（本次 +" + chunk.length() + " 字符，累计 " + merged.length() + " 字符）");
        } catch (Exception e) {
            log.error("appendWorkspaceYaml 失败: path={}", path, e);
            return emitAndReturn(emitter, "追加失败：" + e.getMessage());
        }
    }

    @Tool(name = "submitWorkspaceYaml", description = "读取 workspace YAML，校验后 create/update。新建须 displayName；更新传 fileName")
    public String submitWorkspaceYaml(
            @ToolParam(name = "path", description = "相对路径，如 my-job.yaml") String path,
            @ToolParam(name = "displayName", description = "任务显示名称（新建必填；更新可省略）") String displayName,
            @ToolParam(name = "fileName", description = "配置文件名不含扩展名；更新必填；新建可选") String fileName,
            ToolEmitter emitter,
            RuntimeContext rc) {
        if (!hasClient()) {
            return emitAndReturn(emitter, "未连接数据服务，无法提交配置");
        }
        try {
            String rel = resolveYamlPath(path);
            String yaml = readWorkspace(rc, rel);
            if (yaml == null || yaml.isBlank()) {
                return emitAndReturn(emitter, "workspace 文件不存在或为空：" + rel
                        + "。请先 writeWorkspaceYaml / appendWorkspaceYaml");
            }
            return emitAndReturn(emitter, persistYaml(cleanYamlMarkdown(yaml), displayName, fileName));
        } catch (Exception e) {
            log.error("submitWorkspaceYaml 失败: path={}", path, e);
            return emitAndReturn(emitter, "提交失败：" + e.getMessage());
        }
    }

    // ==================== 内部：落盘 ====================

    private String persistYaml(String yaml, String displayName, String fileName) {
        ValidationResult vr = client.validateYaml(yaml);
        if (!vr.valid()) {
            return "校验失败，未保存：\n" + String.join("\n", vr.errors());
        }

        String normalizedFileName = fileName == null || fileName.isBlank() ? null : fileName.trim();
        try {
            if (normalizedFileName != null) {
                ConfigDetail existing = client.getConfig(normalizedFileName);
                if (existing != null) {
                    String effectiveName = displayName != null && !displayName.isBlank()
                            ? displayName.trim() : existing.name();
                    ConfigDetail saved = client.updateConfig(normalizedFileName, effectiveName, yaml);
                    if (saved == null) {
                        return "更新失败：服务端返回空结果";
                    }
                    return "配置已更新 ✅\n- 文件名: " + saved.fileName() + "\n- 显示名: " + saved.name();
                }
            }

            if (displayName == null || displayName.isBlank()) {
                return "保存失败：新建配置必须提供 displayName（任务显示名）";
            }
            ConfigDetail saved = client.createConfig(displayName.trim(), yaml, normalizedFileName);
            if (saved == null) {
                return "保存失败：服务端返回空结果，请检查 dg-web 连接状态";
            }
            return "配置已保存 ✅\n- 文件名: " + saved.fileName() + "\n- 显示名: " + saved.name();
        } catch (Exception e) {
            log.error("persistYaml 失败: fileName={}", normalizedFileName, e);
            return "保存失败：" + e.getMessage();
        }
    }

    // ==================== 内部：Workspace ====================

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

    static String resolveYamlPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(YAML_DIR + "/")) {
            normalized = normalized.substring(YAML_DIR.length() + 1);
        }
        if (normalized.contains("..") || normalized.contains(":") || normalized.isBlank()) {
            throw new IllegalArgumentException("非法 path：" + path);
        }
        return YAML_DIR + "/" + normalized;
    }

    private void writeWorkspace(RuntimeContext rc, String relativePath, String content) {
        workspaceManager().writeUtf8WorkspaceRelative(rc, relativePath, content);
        try {
            Path diskPath = workspaceManager().getWorkspace().resolve(relativePath);
            Files.createDirectories(diskPath.getParent());
            Files.writeString(diskPath, content);
        } catch (Exception e) {
            log.warn("本地磁盘双写失败: path={}", relativePath, e);
        }
    }

    private String readWorkspace(RuntimeContext rc, String relativePath) {
        try {
            Path diskPath = workspaceManager().getWorkspace().resolve(relativePath);
            if (Files.exists(diskPath)) {
                return Files.readString(diskPath);
            }
        } catch (Exception e) {
            log.debug("磁盘读取失败，回退 WorkspaceManager: path={}", relativePath, e);
        }
        return workspaceManager().readManagedWorkspaceFileUtf8(rc, relativePath);
    }

    // ==================== 格式化辅助 ====================

    static String formatPreviewSummary(List<PreviewTableSummary> tables) {
        if (tables == null || tables.isEmpty()) {
            return "预览成功，但未返回任何表数据。";
        }
        StringBuilder sb = new StringBuilder("预览成功：");
        for (int i = 0; i < tables.size(); i++) {
            PreviewTableSummary table = tables.get(i);
            String name = table.tableName() == null ? "?" : table.tableName();
            int rows = table.rows() == null ? 0 : table.rows().size();
            if (i > 0) {
                sb.append('；');
            }
            sb.append("表 ").append(name).append(' ').append(rows).append(" 行样例");
        }
        sb.append("。请用业务语言向用户说明样例。");
        return sb.toString();
    }

    static List<Map<String, Object>> toWireTables(List<PreviewTableSummary> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(tables.size());
        for (PreviewTableSummary t : tables) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tableName", t.tableName());
            m.put("columns", t.columns() == null ? List.of() : t.columns());
            m.put("rows", t.rows() == null ? List.of() : t.rows());
            out.add(m);
        }
        return out;
    }

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
}
