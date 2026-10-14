package com.datagenerator.ai.client;

import com.datagenerator.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * dg-web REST 客户端 — 通过 HTTP 调用 dg-web 的 API 接口。
 * <p>使用 Spring RestTemplate 封装，自动携带服务间认证头。
 * 当 ai.dg-web.base-url 未配置时（独立模式），该 Bean 不会被创建。</p>
 */
@Component
@ConditionalOnProperty("ai.dg-web.base-url")
public class DgWebClient {

    private static final Logger log = LoggerFactory.getLogger(DgWebClient.class);
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public DgWebClient(AiProperties aiProperties) {
        // 去除结尾斜杠，避免后续字符串拼接产生双斜杠 URL
        String raw = aiProperties.getDgWeb().baseUrl();
        this.baseUrl = raw != null && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        String authToken = aiProperties.getDgWeb().authToken();
        this.restTemplate = new RestTemplate();

        // 如果配置了服务间认证 token，添加到请求头
        if (authToken != null && !authToken.isBlank()) {
            restTemplate.getInterceptors().add((request, body, execution) -> {
                request.getHeaders().set("X-DG-Service-Auth", authToken);
                return execution.execute(request, body);
            });
        }
        log.info("DgWebClient 初始化完成，baseUrl: {}", baseUrl);
    }

    // ==================== Config / TaskConfig ====================

    /** 列出所有已有配置的摘要 */
    public List<ConfigSummary> listConfigs() {
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(
                    baseUrl + "/api/v1/task-configs", JsonNode.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("listConfigs 返回非 2xx: {}", resp.getStatusCode());
                return List.of();
            }
            List<ConfigSummary> result = new ArrayList<>();
            JsonNode body = resp.getBody();
            JsonNode items = body != null && body.has("items") ? body.get("items") : body;
            if (items != null && items.isArray()) {
                for (JsonNode node : items) {
                    result.add(new ConfigSummary(
                            getText(node, "id"),
                            getText(node, "name"),
                            getText(node, "fileName")));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("调用 listConfigs 失败", e);
            return List.of();
        }
    }

    /** 按文件名获取完整配置（含 YAML 原文），不存在返回 null */
    public ConfigDetail getConfig(String fileName) {
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(
                    baseUrl + "/api/v1/task-configs/{name}", JsonNode.class, fileName);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("getConfig({}) 返回非 2xx: {}", fileName, resp.getStatusCode());
                return null;
            }
            JsonNode body = resp.getBody();
            if (body == null) return null;
            return new ConfigDetail(
                    getText(body, "id"),
                    getText(body, "name"),
                    getText(body, "fileName"),
                    getText(body, "content"));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // 404 在 saveConfig / submitWorkspaceYaml 中是正常流程：配置尚不存在 → 新建
            log.debug("getConfig({}) 配置不存在，将作为新建处理", fileName);
            return null;
        } catch (Exception e) {
            log.warn("获取配置失败: {}", fileName, e);
            return null;
        }
    }

    /** 新建配置并持久化。文件名由服务端生成（taskId）。 */
    public ConfigDetail createConfig(String displayName, String yaml) {
        return createConfig(displayName, yaml, null);
    }

    /**
     * 新建配置并持久化。
     *
     * @param fileName 可选；非空时作为任务配置文件名（不含扩展名）
     */
    public ConfigDetail createConfig(String displayName, String yaml, String fileName) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("displayName", displayName);
        request.put("content", yaml);
        if (fileName != null && !fileName.isBlank()) {
            request.put("fileName", fileName.trim());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<JsonNode> resp = restTemplate.exchange(
                baseUrl + "/api/v1/task-configs",
                HttpMethod.POST, entity, JsonNode.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("创建配置失败: HTTP " + resp.getStatusCode());
        }
        return parseConfigDetail(resp.getBody());
    }

    /** 更新已有配置 */
    public ConfigDetail updateConfig(String fileName, String displayName, String yaml) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("displayName", displayName);
        request.put("content", yaml);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<JsonNode> resp = restTemplate.exchange(
                baseUrl + "/api/v1/task-configs/{name}",
                HttpMethod.PUT, entity, JsonNode.class, fileName);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("更新配置失败: HTTP " + resp.getStatusCode());
        }
        return parseConfigDetail(resp.getBody());
    }

    /** 删除配置 */
    public void deleteConfig(String fileName) {
        try {
            restTemplate.delete(baseUrl + "/api/v1/task-configs/{name}", fileName);
        } catch (Exception e) {
            log.warn("删除配置失败: {}", fileName, e);
            throw new RuntimeException("删除配置失败: " + e.getMessage(), e);
        }
    }

    /** 校验 YAML 内容 */
    public ValidationResult validateYaml(String yaml) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("content", yaml);

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v1/task-configs")
                .queryParam("validateOnly", "true")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                    url, HttpMethod.POST, entity, JsonNode.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("validateYaml 返回非 2xx: {}", resp.getStatusCode());
                return new ValidationResult(false, List.of("校验请求失败: HTTP " + resp.getStatusCode()));
            }
            JsonNode body = resp.getBody();
            if (body == null) {
                return new ValidationResult(false, List.of("无响应"));
            }
            boolean valid = body.has("valid") && body.get("valid").asBoolean();
            List<String> errors = new ArrayList<>();
            if (body.has("errors") && body.get("errors").isArray()) {
                body.get("errors").forEach(e -> errors.add(e.asText()));
            }
            return new ValidationResult(valid, errors);
        } catch (Exception e) {
            log.warn("校验 YAML 失败", e);
            return new ValidationResult(false, List.of("校验请求失败: " + e.getMessage()));
        }
    }

    // ==================== 表结构定义（table-schemas API） ====================

    /** 列出所有可用表结构定义名称 */
    public List<String> listTableSchemas() {
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(
                    baseUrl + "/api/v1/table-schemas", JsonNode.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("listTableSchemas 返回非 2xx: {}", resp.getStatusCode());
                return List.of();
            }
            List<String> result = new ArrayList<>();
            if (resp.getBody() != null && resp.getBody().isArray()) {
                resp.getBody().forEach(n -> result.add(n.asText()));
            }
            return result;
        } catch (Exception e) {
            log.warn("调用 listTableSchemas 失败", e);
            return List.of();
        }
    }

    /** 获取表结构定义的字段详情，不存在返回 null */
    public TableSchemaDetail getTableSchema(String name) {
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(
                    baseUrl + "/api/v1/table-schemas/{name}", JsonNode.class, name);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("getTableSchema({}) 返回非 2xx: {}", name, resp.getStatusCode());
                return null;
            }
            JsonNode body = resp.getBody();
            if (body == null || !body.has("fields")) {
                return null;
            }

            List<FieldInfo> fields = new ArrayList<>();
            for (JsonNode f : body.get("fields")) {
                fields.add(new FieldInfo(
                        getText(f, "name"),
                        getText(f, "type"),
                        false)); // dg-web 不提供 nullable 信息
            }
            return new TableSchemaDetail(fields);
        } catch (Exception e) {
            log.warn("获取表结构定义失败: {}", name, e);
            return null;
        }
    }

    // ==================== 预览（已落盘任务） ====================

    /**
     * 按已保存任务文件名预览样例。
     * 对应 {@code POST /api/v1/preview}，body 含 {@code configPath} 与 {@code preview}。
     */
    public PreviewContentResult previewByFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return new PreviewContentResult(false, "fileName 不能为空", List.of());
        }
        String configPath = toConfigPath(fileName.trim());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("configPath", configPath);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("limit", 10);
        request.put("preview", preview);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                    baseUrl + "/api/v1/preview",
                    HttpMethod.POST,
                    entity,
                    JsonNode.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                return new PreviewContentResult(
                        false, "预览失败: HTTP " + resp.getStatusCode(), List.of());
            }
            JsonNode body = resp.getBody();
            if (body == null) {
                return new PreviewContentResult(false, "预览失败: 无响应", List.of());
            }
            List<PreviewTableSummary> tables = parsePreviewTables(body);
            return new PreviewContentResult(true, null, tables);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return new PreviewContentResult(
                    false, extractErrorMessage(e, "预览失败"), List.of());
        } catch (Exception e) {
            log.warn("调用 previewByFileName 失败: fileName={}", fileName, e);
            return new PreviewContentResult(false, "预览失败: " + e.getMessage(), List.of());
        }
    }

    /** 与 dg-web TaskConfigPaths.toConfigPath 对齐：fileName → task-configs/{name}.yaml */
    static String toConfigPath(String fileName) {
        String normalized = fileName.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("task-configs/")) {
            normalized = normalized.substring("task-configs/".length());
        }
        if (normalized.endsWith(".yaml") || normalized.endsWith(".yml")) {
            return "task-configs/" + normalized;
        }
        return "task-configs/" + normalized + ".yaml";
    }

    private static List<PreviewTableSummary> parsePreviewTables(JsonNode body) {
        if (body == null || !body.has("tables") || !body.get("tables").isArray()) {
            return List.of();
        }
        List<PreviewTableSummary> tables = new ArrayList<>();
        for (JsonNode t : body.get("tables")) {
            String tableName = getText(t, "tableName");
            List<String> columns = new ArrayList<>();
            if (t.has("columns") && t.get("columns").isArray()) {
                t.get("columns").forEach(c -> columns.add(c.asText()));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            if (t.has("rows") && t.get("rows").isArray()) {
                for (JsonNode rowNode : t.get("rows")) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    if (rowNode != null && rowNode.isObject()) {
                        rowNode.fields().forEachRemaining(e -> row.put(e.getKey(), jsonToSimple(e.getValue())));
                    }
                    rows.add(row);
                }
            }
            tables.add(new PreviewTableSummary(tableName, columns, rows));
        }
        return tables;
    }

    private static Object jsonToSimple(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    // ==================== 连接探查 ====================

    /**
     * 测通远程连接。对应 {@code POST /api/v1/connections/test}。
     * <p>probe 字段与 dg-web ConnectionProbeRequest 对齐：
     * type / url / path / username / password / schema / pattern。</p>
     */
    public ConnectionTestResult testConnection(Map<String, Object> probe) {
        try {
            ResponseEntity<JsonNode> resp = postProbe("/api/v1/connections/test", probe);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                return new ConnectionTestResult(false, "连接测试失败: HTTP " + resp.getStatusCode());
            }
            JsonNode body = resp.getBody();
            if (body == null) {
                return new ConnectionTestResult(false, "连接测试失败: 无响应");
            }
            boolean ok = body.has("ok") && body.get("ok").asBoolean();
            String message = getText(body, "message");
            return new ConnectionTestResult(ok, message);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return new ConnectionTestResult(false, extractErrorMessage(e, "连接测试失败"));
        } catch (Exception e) {
            log.warn("调用 testConnection 失败", e);
            return new ConnectionTestResult(false, "连接测试失败: " + e.getMessage());
        }
    }

    /**
     * 列出远程库表。对应 {@code POST /api/v1/connections/tables}。
     */
    public List<String> listRemoteTables(Map<String, Object> probe) {
        try {
            ResponseEntity<JsonNode> resp = postProbe("/api/v1/connections/tables", probe);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("列出表失败: HTTP " + resp.getStatusCode());
            }
            JsonNode body = resp.getBody();
            if (body == null || !body.has("tables") || !body.get("tables").isArray()) {
                return List.of();
            }
            List<String> tables = new ArrayList<>();
            body.get("tables").forEach(n -> tables.add(n.asText()));
            return tables;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw new RuntimeException(extractErrorMessage(e, "列出表失败"), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("调用 listRemoteTables 失败", e);
            throw new RuntimeException("列出表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 列出远程表字段。对应 {@code POST /api/v1/connections/tables/{table}/columns}。
     */
    public List<RemoteColumn> listRemoteColumns(Map<String, Object> probe, String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名 table 不能为空");
        }
        try {
            String encodedTable = UriUtils.encodePathSegment(table.trim(), StandardCharsets.UTF_8);
            ResponseEntity<JsonNode> resp = postProbe(
                    "/api/v1/connections/tables/" + encodedTable + "/columns", probe);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("列出字段失败: HTTP " + resp.getStatusCode());
            }
            JsonNode body = resp.getBody();
            if (body == null || !body.has("columns") || !body.get("columns").isArray()) {
                return List.of();
            }
            List<RemoteColumn> columns = new ArrayList<>();
            for (JsonNode c : body.get("columns")) {
                columns.add(new RemoteColumn(
                        getText(c, "name"),
                        getText(c, "typeName"),
                        c.has("nullable") && c.get("nullable").asBoolean(),
                        c.has("primaryKey") && c.get("primaryKey").asBoolean()));
            }
            return columns;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw new RuntimeException(extractErrorMessage(e, "列出字段失败"), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("调用 listRemoteColumns 失败: table={}", table, e);
            throw new RuntimeException("列出字段失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部 Record ====================

    /** 配置摘要 */
    public record ConfigSummary(String id, String name, String fileName) {}

    /** 配置详情（含 YAML 原文） */
    public record ConfigDetail(String id, String name, String fileName, String yaml) {}

    /** YAML 校验结果 */
    public record ValidationResult(boolean valid, List<String> errors) {}

    /** 表结构定义字段详情 */
    public record TableSchemaDetail(List<FieldInfo> fields) {}

    /** 字段信息 */
    public record FieldInfo(String name, String type, boolean nullable) {}

    /** 连接测通结果 */
    public record ConnectionTestResult(boolean ok, String message) {}

    /** 远程字段元数据 */
    public record RemoteColumn(
            String name, String typeName, boolean nullable, boolean primaryKey) {}

    /** 未落盘 YAML 预览结果 */
    public record PreviewContentResult(boolean ok, String message, List<PreviewTableSummary> tables) {}

    /** 预览表摘要（行数据供 Agent 摘要，不对用户贴完整 YAML） */
    public record PreviewTableSummary(
            String tableName, List<String> columns, List<Map<String, Object>> rows) {}

    // ==================== 内部工具 ====================

    private ResponseEntity<JsonNode> postProbe(String apiPath, Map<String, Object> probe) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                probe != null ? probe : Map.of(), headers);
        return restTemplate.exchange(
                baseUrl + apiPath, HttpMethod.POST, entity, JsonNode.class);
    }

    /** 从 4xx/5xx 响应体提取 message（与 dg-web ErrorResponse 对齐） */
    private static String extractErrorMessage(
            org.springframework.web.client.HttpStatusCodeException e, String fallback) {
        try {
            String body = e.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                JsonNode node = ERROR_MAPPER.readTree(body);
                String message = getText(node, "message");
                if (message != null && !message.isBlank()) {
                    return message;
                }
            }
        } catch (Exception ignored) {
            // 解析失败则回退
        }
        String statusText = e.getStatusText();
        if (statusText != null && !statusText.isBlank()) {
            return fallback + ": " + statusText;
        }
        return fallback;
    }

    /** 从 JsonNode 中安全提取文本字段 */
    private static String getText(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return f != null && !f.isNull() ? f.asText() : null;
    }

    /** 将 JsonNode 解析为 ConfigDetail */
    private static ConfigDetail parseConfigDetail(JsonNode body) {
        if (body == null) return null;
        return new ConfigDetail(
                getText(body, "id"),
                getText(body, "name"),
                getText(body, "fileName"),
                getText(body, "content"));
    }
}
