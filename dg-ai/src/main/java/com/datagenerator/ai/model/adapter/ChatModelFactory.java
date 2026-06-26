package com.datagenerator.ai.model.adapter;

import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.web.dto.response.ProviderInfo;
import org.springframework.util.StringUtils;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** LangChain4j 模型适配：按配置创建 StreamingChatModel。 */
public class ChatModelFactory {

    private final AiProperties aiProperties;

    public ChatModelFactory(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public StreamingChatModel getStreamingModel(String providerId) {
        AiProperties.ProviderProperties props = aiProperties.getProviders().get(providerId);
        if (props == null) {
            throw new IllegalArgumentException("Unknown AI provider: " + providerId);
        }
        return createStreaming(props);
    }

    public Set<String> availableProviders() {
        Set<String> available = new LinkedHashSet<>();
        for (Map.Entry<String, AiProperties.ProviderProperties> entry
                : aiProperties.getProviders().entrySet()) {
            if (isProviderConfigured(entry.getValue())) {
                available.add(entry.getKey());
            }
        }
        return available;
    }

    public List<ProviderInfo> listProviderInfos() {
        String defaultId = aiProperties.getDefaultProvider();
        List<ProviderInfo> infos = new ArrayList<>();
        for (Map.Entry<String, AiProperties.ProviderProperties> entry
                : aiProperties.getProviders().entrySet()) {
            AiProperties.ProviderProperties props = entry.getValue();
            if (!isProviderConfigured(props)) {
                continue;
            }
            String id = entry.getKey();
            infos.add(new ProviderInfo(
                    id,
                    formatProviderLabel(id, props),
                    props.getModel(),
                    id.equals(defaultId)));
        }
        return infos;
    }

    private static boolean isProviderConfigured(AiProperties.ProviderProperties props) {
        if (props == null) {
            return false;
        }
        return StringUtils.hasText(props.getApiKey()) || "ollama".equals(props.getType());
    }

    private static String formatProviderLabel(String id, AiProperties.ProviderProperties props) {
        String base = switch (id) {
            case "deepseek" -> "DeepSeek";
            case "openai" -> "OpenAI";
            case "ollama" -> "Ollama";
            default -> id;
        };
        if (StringUtils.hasText(props.getModel())) {
            return base + " · " + props.getModel();
        }
        return base;
    }

    private StreamingChatModel createStreaming(AiProperties.ProviderProperties props) {
        Duration timeout = props.getTimeout() != null
                ? props.getTimeout()
                : aiProperties.getRequestTimeout();
        Integer maxOutputTokens = resolveMaxOutputTokens(props);
        return switch (props.getType()) {
            case "open-ai-compatible" -> {
                OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder =
                        OpenAiStreamingChatModel.builder()
                                .baseUrl(props.getBaseUrl())
                                .apiKey(props.getApiKey())
                                .modelName(props.getModel())
                                .timeout(timeout);
                if (maxOutputTokens != null) {
                    builder.maxTokens(maxOutputTokens);
                }
                yield new NullSafeStreamingChatModel(builder.build());
            }
            case "ollama" -> {
                OllamaStreamingChatModel.OllamaStreamingChatModelBuilder builder =
                        OllamaStreamingChatModel.builder()
                                .baseUrl(props.getBaseUrl())
                                .modelName(props.getModel())
                                .timeout(timeout);
                if (maxOutputTokens != null) {
                    builder.numPredict(maxOutputTokens);
                }
                yield new NullSafeStreamingChatModel(builder.build());
            }
            default -> throw new IllegalArgumentException("Unknown provider type: " + props.getType());
        };
    }

    private Integer resolveMaxOutputTokens(AiProperties.ProviderProperties props) {
        if (props.getMaxOutputTokens() != null) {
            return props.getMaxOutputTokens();
        }
        return aiProperties.getMaxOutputTokens();
    }
}
