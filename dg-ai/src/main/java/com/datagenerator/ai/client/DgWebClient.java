package com.datagenerator.ai.client;

import com.datagenerator.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * dg-web REST 客户端 — 通过 HTTP 调用 dg-web 的 API 接口。
 * <p>使用 Spring RestTemplate 封装，自动携带服务间认证头。
 * 当 ai.dg-web.base-url 未配置时（独立模式），该 Bean 不会被创建。</p>
 */
@Component
@ConditionalOnProperty("ai.dg-web.base-url")
public class DgWebClient {

    private static final Logger log = LoggerFactory.getLogger(DgWebClient.class);

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

    // ==================== Config / JobDefinition ====================

    /** 列出所有已有配置的摘要 */
    public List<ConfigSummary> listConfigs() {
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(
                    baseUrl + "/api/v1/job-definitions", JsonNode.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("listConfigs 返回非 2xx: {}", resp.getStatusCode());
                return List.of();
            }
            List<ConfigSummary> result = new ArrayList<>();
            if (resp.getBody() != null && resp.getBody().isArray()) {
                for (JsonNode node : resp.getBody()) {
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
                    baseUrl + "/api/v1/job-definitions/{name}", JsonNode.class, fileName);
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
            // 404 在 saveConfigDraft 中是正常流程：配置尚不存在 → 新建而非更新
            log.debug("getConfig({}) 配置不存在，将作为新建处理", fileName);
            return null;
        } catch (Exception e) {
            log.warn("获取配置失败: {}", fileName, e);
            return null;
        }
    }

    /** 新建配置并持久化。文件名由服务端根据 YAML id 自动生成。 */
    public ConfigDetail createConfig(String displayName, String yaml) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("displayName", displayName);
        request.put("content", yaml);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<JsonNode> resp = restTemplate.exchange(
                baseUrl + "/api/v1/job-definitions",
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
                baseUrl + "/api/v1/job-definitions/{name}",
                HttpMethod.PUT, entity, JsonNode.class, fileName);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("更新配置失败: HTTP " + resp.getStatusCode());
        }
        return parseConfigDetail(resp.getBody());
    }

    /** 删除配置 */
    public void deleteConfig(String fileName) {
        try {
            restTemplate.delete(baseUrl + "/api/v1/job-definitions/{name}", fileName);
        } catch (Exception e) {
            log.warn("删除配置失败: {}", fileName, e);
            throw new RuntimeException("删除配置失败: " + e.getMessage(), e);
        }
    }

    /** 校验 YAML 内容 */
    public ValidationResult validateYaml(String yaml) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("content", yaml);

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v1/job-definitions")
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

    // ==================== Schema ====================

    /** 列出所有可用 Schema 名称 */
    public List<String> listSchemas() {
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(
                    baseUrl + "/api/v1/schemas", JsonNode.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("listSchemas 返回非 2xx: {}", resp.getStatusCode());
                return List.of();
            }
            List<String> result = new ArrayList<>();
            if (resp.getBody() != null && resp.getBody().isArray()) {
                resp.getBody().forEach(n -> result.add(n.asText()));
            }
            return result;
        } catch (Exception e) {
            log.warn("调用 listSchemas 失败", e);
            return List.of();
        }
    }

    /** 获取 Schema 的字段详情，不存在返回 null */
    public SchemaDetail getSchema(String name) {
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(
                    baseUrl + "/api/v1/schemas/{name}", JsonNode.class, name);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("getSchema({}) 返回非 2xx: {}", name, resp.getStatusCode());
                return null;
            }
            JsonNode body = resp.getBody();
            if (body == null || !body.has("fields")) return null;

            List<FieldInfo> fields = new ArrayList<>();
            for (JsonNode f : body.get("fields")) {
                fields.add(new FieldInfo(
                        getText(f, "name"),
                        getText(f, "type"),
                        false)); // dg-web 不提供 nullable 信息
            }
            return new SchemaDetail(fields);
        } catch (Exception e) {
            log.warn("获取 Schema 失败: {}", name, e);
            return null;
        }
    }

    // ==================== Connection ====================

    /** 列出所有可用数据连接的摘要 */
    public List<ConnectionInfo> listConnections() {
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(
                    baseUrl + "/api/v1/config/connections", JsonNode.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("listConnections 返回非 2xx: {}", resp.getStatusCode());
                return List.of();
            }
            List<ConnectionInfo> result = new ArrayList<>();
            if (resp.getBody() != null && resp.getBody().isArray()) {
                for (JsonNode node : resp.getBody()) {
                    result.add(new ConnectionInfo(
                            getText(node, "name"),
                            getText(node, "type")));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("调用 listConnections 失败", e);
            return List.of();
        }
    }

    /** 获取指定连接的完整详情，不存在返回 null */
    public ConnectionDetail getConnectionDetail(String name) {
        List<ConnectionInfo> conns = listConnections();
        for (ConnectionInfo c : conns) {
            if (c.name().equals(name)) {
                return new ConnectionDetail(c.name(), c.type(), null, null, null);
            }
        }
        return null;
    }

    // ==================== 内部 Record ====================

    /** 配置摘要 */
    public record ConfigSummary(String id, String name, String fileName) {}

    /** 配置详情（含 YAML 原文） */
    public record ConfigDetail(String id, String name, String fileName, String yaml) {}

    /** YAML 校验结果 */
    public record ValidationResult(boolean valid, List<String> errors) {}

    /** Schema 字段详情 */
    public record SchemaDetail(List<FieldInfo> fields) {}

    /** 字段信息 */
    public record FieldInfo(String name, String type, boolean nullable) {}

    /** 数据连接摘要 */
    public record ConnectionInfo(String name, String type) {}

    /** 数据连接详情 */
    public record ConnectionDetail(String name, String type, String url, String username, String password) {}

    // ==================== 内部工具 ====================

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
