package com.datagenerator.ai.embedding;

import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;

/**
 * OpenAI-compatible embedding 模型，不发送 {@code dimensions} 参数。
 *
 * <p>与 agentScope 内置的 {@code OpenAITextEmbedding} 不同，本实现直接通过
 * {@link RestTemplate} 调用 OpenAI-compatible API，请求体中仅包含
 * {@code model} 和 {@code input}，不会传递 {@code dimensions} 参数。
 * 这避免了 SiliconFlow、Ollama 等不支持该参数的服务商返回 400 错误。</p>
 *
 * <p>适用场景：
 * <ul>
 *   <li>SiliconFlow（硅基流动）的 BGE 系列模型</li>
 *   <li>Ollama 的 OpenAI-compatible 端点</li>
 *   <li>其他不支持 {@code dimensions} 参数的 OpenAI-compatible 服务</li>
 * </ul>
 */
public class SimpleEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(SimpleEmbeddingModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final RestTemplate restTemplate;

    /**
     * @param baseUrl   API 基础地址（如 {@code https://api.siliconflow.cn/v1}）
     * @param apiKey    API 密钥（可为空）
     * @param modelName embedding 模型名称
     * @param dimensions 预期向量维度（仅用于 InMemoryStore 配置和校验，不发送到 API）
     */
    public SimpleEmbeddingModel(String baseUrl, String apiKey, String modelName, int dimensions) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.restTemplate = new RestTemplate();
        log.info("SimpleEmbeddingModel 初始化完成: baseUrl={}, model={}, dimensions={}",
                this.baseUrl, modelName, dimensions);
    }

    @Override
    public Mono<double[]> embed(ContentBlock contentBlock) {
        if (contentBlock == null) {
            return Mono.error(new EmbeddingException(
                    "ContentBlock cannot be null", modelName, "openai-compatible"));
        }
        if (!(contentBlock instanceof TextBlock textBlock)) {
            return Mono.error(new EmbeddingException(
                    "不支持的 ContentBlock 类型: " + contentBlock.getClass().getSimpleName(),
                    modelName, "openai-compatible"));
        }

        String text = textBlock.getText();
        if (text == null || text.trim().isEmpty()) {
            return Mono.error(new EmbeddingException(
                    "TextBlock text cannot be null or empty", modelName, "openai-compatible"));
        }

        return Mono.fromCallable(() -> doEmbed(text))
                .onErrorMap(e -> {
                    if (e instanceof EmbeddingException ee) {
                        return ee;
                    }
                    return new EmbeddingException(
                            "Failed to generate embedding: " + e.getMessage(),
                            e, modelName, "openai-compatible");
                });
    }

    private double[] doEmbed(String text) {
        log.debug("Embedding 调用: model={}, text_length={}", modelName, text.length());

        // 构建请求体：仅含 model 和 input，不含 dimensions
        Map<String, Object> requestBody = Map.of(
                "model", modelName,
                "input", text
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            String url = baseUrl + "/embeddings";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new EmbeddingException(
                        "Embedding API 返回异常: " + response.getStatusCode(),
                        modelName, "openai-compatible");
            }

            return parseResponse(response.getBody());
        } catch (RestClientException e) {
            throw new EmbeddingException(
                    "Embedding API 调用失败: " + e.getMessage(), e,
                    modelName, "openai-compatible");
        }
    }

    @SuppressWarnings("unchecked")
    private double[] parseResponse(Map<String, Object> body) {
        // OpenAI-compatible 响应格式: { "data": [ { "embedding": [1.2, 3.4, ...] } ] }
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        if (data == null || data.isEmpty()) {
            throw new EmbeddingException(
                    "Empty response from embedding API", modelName, "openai-compatible");
        }

        Map<String, Object> firstItem = data.get(0);
        Object embeddingObj = firstItem.get("embedding");
        if (embeddingObj == null) {
            throw new EmbeddingException(
                    "No embedding data in response", modelName, "openai-compatible");
        }

        double[] vector;
        if (embeddingObj instanceof List<?> list) {
            vector = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object val = list.get(i);
                vector[i] = val instanceof Number n ? n.doubleValue() : 0.0;
            }
        } else {
            throw new EmbeddingException(
                    "无法解析 embedding 向量类型: " + embeddingObj.getClass().getName(),
                    modelName, "openai-compatible");
        }

        if (dimensions > 0 && vector.length != dimensions) {
            log.warn("Embedding 维度不匹配: 期望={}, 实际={}", dimensions, vector.length);
        }

        return vector;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }
}
