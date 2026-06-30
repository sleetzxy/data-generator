package com.datagenerator.ai.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private boolean enabled = false;
    private boolean server = false;
    private RemoteServices remoteServices = new RemoteServices();
    private Duration requestTimeout = Duration.ofSeconds(120);
    private SessionProperties session = new SessionProperties();
    private Map<String, ProviderProperties> providers = new HashMap<>();

    /** Agent 异步执行线程池大小 (绑定配置键: ai.agent-thread-pool-size) */
    private int agentThreadPoolSize = 10;

    /** Agent 输入/输出调试日志 */
    private IoLoggingProperties ioLogging = new IoLoggingProperties();

    /** 各 Agent 的 Tool Set 绑定 */
    private Map<String, AgentProperties> agents = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isServer() {
        return server;
    }

    public void setServer(boolean server) {
        this.server = server;
    }

    public RemoteServices getRemoteServices() {
        return remoteServices;
    }

    public void setRemoteServices(RemoteServices remoteServices) {
        this.remoteServices = remoteServices;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public SessionProperties getSession() {
        return session;
    }

    public void setSession(SessionProperties session) {
        this.session = session;
    }

    public Map<String, ProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderProperties> providers) {
        this.providers = providers;
    }

    public int getAgentThreadPoolSize() {
        return agentThreadPoolSize;
    }

    public void setAgentThreadPoolSize(int agentThreadPoolSize) {
        this.agentThreadPoolSize = agentThreadPoolSize;
    }

    public IoLoggingProperties getIoLogging() {
        return ioLogging;
    }

    public void setIoLogging(IoLoggingProperties ioLogging) {
        this.ioLogging = ioLogging != null ? ioLogging : new IoLoggingProperties();
    }

    public Map<String, AgentProperties> getAgents() {
        return agents;
    }

    public void setAgents(Map<String, AgentProperties> agents) {
        this.agents = agents != null ? agents : new HashMap<>();
    }

    /** Tool Port 回调的外部 HTTP 服务 */
    public static class RemoteServices {

        /** dg-web 现有 REST API */
        private ServiceEndpoint dataGeneratorWeb = new ServiceEndpoint();

        public ServiceEndpoint getDataGeneratorWeb() {
            return dataGeneratorWeb;
        }

        public void setDataGeneratorWeb(ServiceEndpoint dataGeneratorWeb) {
            this.dataGeneratorWeb = dataGeneratorWeb;
        }
    }

    public static class ServiceEndpoint {

        private String baseUrl;
        /** 与 dg-web data-generator.service-auth.token 一致，经 X-DG-Service-Auth 请求头传递 */
        private String serviceAuthToken;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getServiceAuthToken() {
            return serviceAuthToken;
        }

        public void setServiceAuthToken(String serviceAuthToken) {
            this.serviceAuthToken = serviceAuthToken;
        }
    }

    public static class SessionProperties {

        private Duration ttl = Duration.ofHours(2);
        private int maxSessions = 100;

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
        }
    }

    public static class ProviderProperties {

        private String type;
        private String baseUrl;
        private String apiKey;
        private String model;
        private Duration timeout;
        private Integer maxOutputTokens;
        private Double temperature;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Integer getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }
    }

    /** Agent 对话 I/O 日志配置（{@code ai.io-logging.*}） */
    public static class IoLoggingProperties {

        /** 是否打印模型输入/输出、Tool 调用、草稿合并决策 */
        private boolean enabled = false;
        /** 单段日志最大字符数；0 表示不截断 */
        private int maxChars = 8_192;
        /** 是否记录 Tool 返回体 */
        private boolean logToolResults = true;
        /** 是否记录推送给前端的 SSE token/提示 */
        private boolean logSseEvents = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public boolean isLogToolResults() {
            return logToolResults;
        }

        public void setLogToolResults(boolean logToolResults) {
            this.logToolResults = logToolResults;
        }

        public boolean isLogSseEvents() {
            return logSseEvents;
        }

        public void setLogSseEvents(boolean logSseEvents) {
            this.logSseEvents = logSseEvents;
        }
    }

    /** 按 providerId 创建对应的 StreamingChatModel。 */
    public StreamingChatModel createStreamingModel(String providerId) {
        ProviderProperties props = providers.get(providerId);
        if (props == null) {
            throw new IllegalArgumentException("Unknown AI provider: " + providerId);
        }
        return buildModel(props);
    }

    /** 返回所有已配置（有 apiKey 或 ollama）的 provider ID。 */
    public Set<String> availableProviders() {
        Set<String> available = new LinkedHashSet<>();
        for (Map.Entry<String, ProviderProperties> entry : providers.entrySet()) {
            ProviderProperties p = entry.getValue();
            if (isProviderConfigured(p)) {
                available.add(entry.getKey());
            }
        }
        return available;
    }

    private StreamingChatModel buildModel(ProviderProperties props) {
        Duration timeout = props.getTimeout() != null
                ? props.getTimeout()
                : requestTimeout;
        Integer maxOutputTokens = props.getMaxOutputTokens();
        Double temperature = props.getTemperature();
        return switch (props.getType()) {
            case "open-ai-compatible" -> {
                var builder = OpenAiStreamingChatModel.builder()
                        .baseUrl(props.getBaseUrl())
                        .apiKey(props.getApiKey())
                        .modelName(props.getModel())
                        .timeout(timeout);
                if (maxOutputTokens != null) builder.maxTokens(maxOutputTokens);
                if (temperature != null) builder.temperature(temperature);
                yield builder.build();
            }
            case "ollama" -> {
                var builder = OllamaStreamingChatModel.builder()
                        .baseUrl(props.getBaseUrl())
                        .modelName(props.getModel())
                        .timeout(timeout);
                if (maxOutputTokens != null) builder.numPredict(maxOutputTokens);
                if (temperature != null) builder.temperature(temperature);
                yield builder.build();
            }
            default -> throw new IllegalArgumentException("Unknown provider type: " + props.getType());
        };
    }

    private static boolean isProviderConfigured(ProviderProperties props) {
        if (props == null) {
            return false;
        }
        return StringUtils.hasText(props.getApiKey()) || "ollama".equals(props.getType());
    }

    /** 各 Agent 的配置（{@code ai.agents.<id>.*}） */
    public static class AgentProperties {

        /** Agent 绑定的默认 Provider（{@code ai.agents.<id>.provider}） */
        private String provider;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

    }
}
