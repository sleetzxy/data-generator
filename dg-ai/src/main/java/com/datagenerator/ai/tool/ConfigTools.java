package com.datagenerator.ai.tool;

import com.datagenerator.ai.client.DgWebClient;
import com.datagenerator.ai.client.DgWebClient.ConfigDetail;
import com.datagenerator.ai.client.DgWebClient.ConfigSummary;
import com.datagenerator.ai.client.DgWebClient.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 配置管理工具集 — 供 HarnessAgent ReAct 循环调用。
 * 只保留 CRUD、环境查询、校验保存，去掉片段暂存和硬编码文档。
 */
@Component
public class ConfigTools {

    private static final Logger log = LoggerFactory.getLogger(ConfigTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ID_PATTERN = Pattern.compile("^id:\\s*\"?([a-zA-Z0-9_-]+)\"?\\s*$", Pattern.MULTILINE);

    private final DgWebClient client;
    private final KnowledgeTools knowledgeTools;

    public ConfigTools(Optional<DgWebClient> client, KnowledgeTools knowledgeTools) {
        this.client = client.orElse(null);
        this.knowledgeTools = knowledgeTools;
    }

    private boolean hasClient() { return client != null; }

    // ==================== CRUD ====================

    @Tool(name = "listConfigs", description = "列出所有已有 Job 配置")
    public String listConfigs() {
        if (!hasClient()) return "未连接数据服务，无法查询配置";
        List<ConfigSummary> list = client.listConfigs();
        if (list.isEmpty()) return "暂无已有配置";
        StringBuilder sb = new StringBuilder("已有配置：\n");
        for (ConfigSummary s : list) {
            sb.append("- ").append(s.name()).append(" (").append(s.fileName()).append(")\n");
        }
        return sb.toString();
    }

    @Tool(name = "getConfig", description = "查看指定配置的完整 YAML 内容，参数 fileName")
    public String getConfig(String fileName) {
        if (!hasClient()) return "未连接数据服务，无法查询配置";
        ConfigDetail d = client.getConfig(fileName);
        if (d == null) return "未找到配置：" + fileName;
        return "配置「" + d.name() + "」(" + d.fileName() + ")：\n```yaml\n" + d.yaml() + "\n```";
    }

    @Tool(name = "deleteConfig", description = "删除指定配置，参数 fileName。删除前需向用户确认")
    public String deleteConfig(String fileName) {
        if (!hasClient()) return "未连接数据服务，无法删除配置";
        ConfigDetail d = client.getConfig(fileName);
        if (d == null) return "未找到配置：" + fileName;
        client.deleteConfig(fileName);
        return "已删除配置：「" + d.name() + "」(" + fileName + ")";
    }

    @Tool(name = "loadConfigForEdit", description = "加载已有配置用于编辑，返回完整 YAML 内容供写入 Workspace")
    public String loadConfigForEdit(String fileName) {
        if (!hasClient()) return "未连接数据服务，无法加载配置";
        ConfigDetail d = client.getConfig(fileName);
        if (d == null) return "未找到配置：" + fileName;
        return "已加载配置「" + d.name() + "」(" + d.fileName() + ")，请将其写入 Workspace：\n```yaml\n" + d.yaml() + "\n```";
    }

    // ==================== 环境查询 ====================

    @Tool(name = "listConnections", description = "查询所有可用数据库连接")
    public String listConnections() {
        if (!hasClient()) return "当前无可用的数据连接（未连接数据服务）";
        var conns = client.listConnections();
        if (conns == null || conns.isEmpty()) return "当前无可用数据连接";
        StringBuilder sb = new StringBuilder("可用连接：\n");
        conns.forEach(c -> sb.append("- ").append(c.name()).append(" (").append(c.type()).append(")\n"));
        return sb.toString();
    }

    @Tool(name = "listSchemas", description = "查询所有可用 Schema 定义")
    public String listSchemas() {
        if (!hasClient()) return "当前无可用的 Schema（未连接数据服务）";
        var names = client.listSchemas();
        if (names == null || names.isEmpty()) return "当前无可用 Schema";
        StringBuilder sb = new StringBuilder("可用 Schema：\n");
        for (String name : names) {
            var d = client.getSchema(name);
            if (d != null && d.fields() != null) {
                sb.append("- ").append(name).append(": ");
                d.fields().forEach(f -> sb.append(f.name()).append("(").append(f.type()).append(") "));
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    @Tool(name = "getSchema", description = "查看某个 Schema 的字段详情")
    public String getSchema(String name) {
        if (!hasClient()) return "未连接数据服务，无法查询 Schema";
        var d = client.getSchema(name);
        if (d == null) return "未找到 Schema：" + name;
        StringBuilder sb = new StringBuilder("Schema「").append(name).append("」字段：\n");
        d.fields().forEach(f -> sb.append("- ").append(f.name())
                .append(" (").append(f.type()).append(")\n"));
        return sb.toString();
    }

    // ==================== 校验与保存 ====================

    @Tool(name = "validateYaml", description = "校验 YAML 语法和业务规则，返回校验结果")
    public String validateYaml(String yaml) {
        if (yaml == null || yaml.isBlank()) return "错误：YAML 为空";
        if (!hasClient()) return "独立模式，跳过校验";
        String cleaned = cleanYamlMarkdown(yaml);
        ValidationResult vr = client.validateYaml(cleaned);
        if (!vr.valid()) {
            log.warn("YAML 校验失败: {}", vr.errors());
            return "YAML 校验失败：\n" + String.join("\n", vr.errors());
        }
        return "校验通过 ✅";
    }

    @Tool(name = "saveConfig", description = "保存配置到 dg-web。传入 configName（显示名称）和完整 YAML 内容")
    public String saveConfig(String configName, String yaml) {
        if (yaml == null || yaml.isBlank()) return "错误：YAML 内容为空";
        if (!hasClient()) {
            return "独立模式，未连接 dg-web，未保存。YAML 内容：\n```yaml\n" + cleanYamlMarkdown(yaml) + "\n```";
        }

        String cleaned = cleanYamlMarkdown(yaml);
        ValidationResult vr = client.validateYaml(cleaned);
        if (!vr.valid()) {
            return "校验失败，未保存：\n" + String.join("\n", vr.errors());
        }

        String fileName = extractConfigId(cleaned);
        try {
            ConfigDetail existing = client.getConfig(fileName);
            ConfigDetail saved;
            if (existing != null) {
                saved = client.updateConfig(fileName, configName, cleaned);
            } else {
                saved = client.createConfig(fileName, configName, cleaned);
            }
            return "配置已保存 ✅\n- 文件名: " + saved.fileName() + "\n- 显示名: " + saved.name();
        } catch (Exception e) {
            log.error("保存配置失败", e);
            return "保存失败：" + e.getMessage();
        }
    }

    // ==================== 内部方法 ====================

    private static String cleanYamlMarkdown(String yaml) {
        return yaml.replaceAll("^```yaml\\s*", "").replaceAll("\\s*```$", "").trim();
    }

    private static String extractConfigId(String yaml) {
        Matcher m = ID_PATTERN.matcher(yaml);
        if (m.find()) {
            return m.group(1);
        }
        return "generated-" + System.currentTimeMillis();
    }
}
