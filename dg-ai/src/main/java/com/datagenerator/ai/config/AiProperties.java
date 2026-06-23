package com.datagenerator.ai.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private boolean enabled = false;
    private boolean server = false;
    private RemoteServices remoteServices = new RemoteServices();
    private String defaultProvider;
    private int chatMemoryMaxMessages = 40;
    /** 模型单次回复最大输出 token，Tool 调用参数过长时可适当增大 */
    private Integer maxOutputTokens = 8192;
    private Duration requestTimeout = Duration.ofSeconds(120);
    private SessionProperties session = new SessionProperties();
    private Map<String, ProviderProperties> providers = new HashMap<>();

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

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public int getChatMemoryMaxMessages() {
        return chatMemoryMaxMessages;
    }

    public void setChatMemoryMaxMessages(int chatMemoryMaxMessages) {
        this.chatMemoryMaxMessages = chatMemoryMaxMessages;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
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
    }
}
