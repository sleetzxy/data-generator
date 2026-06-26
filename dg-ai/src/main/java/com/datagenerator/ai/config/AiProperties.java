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
    /** 对话记忆 token 上限；大于 0 时启用 TokenWindowChatMemory，否则回退到条数窗口 */
    private int chatMemoryMaxTokens = 48_000;
    /** Tool 结果写入 memory 前的最大字符数；列表/校验类 Tool 在白名单内不截断 */
    private int chatMemoryToolResultMaxChars = 32_768;

    /** @deprecated 使用 {@link #chatMemoryToolResultMaxChars} */
    @Deprecated
    private int chatMemoryInlineMaxChars = 32_768;
    /** 模型单次回复最大输出 token，Tool 调用参数过长时可适当增大 */
    private Integer maxOutputTokens = 8192;
    private Duration requestTimeout = Duration.ofSeconds(120);
    private SessionProperties session = new SessionProperties();
    private Map<String, ProviderProperties> providers = new HashMap<>();

    /** Agent 异步执行线程池大小 (绑定配置键: ai.agent-thread-pool-size) */
    private int agentThreadPoolSize = 10;

    /** Agent 输入/输出调试日志 */
    private IoLoggingProperties ioLogging = new IoLoggingProperties();

    /** 结构化 YAML 草稿自动续写阈值 */
    private DraftContinueProperties draftContinue = new DraftContinueProperties();

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

    public int getChatMemoryMaxTokens() {
        return chatMemoryMaxTokens;
    }

    public void setChatMemoryMaxTokens(int chatMemoryMaxTokens) {
        this.chatMemoryMaxTokens = chatMemoryMaxTokens;
    }

    public int getChatMemoryInlineMaxChars() {
        return chatMemoryInlineMaxChars;
    }

    public void setChatMemoryInlineMaxChars(int chatMemoryInlineMaxChars) {
        this.chatMemoryInlineMaxChars = chatMemoryInlineMaxChars;
        this.chatMemoryToolResultMaxChars = chatMemoryInlineMaxChars;
    }

    public int getChatMemoryToolResultMaxChars() {
        return chatMemoryToolResultMaxChars;
    }

    public void setChatMemoryToolResultMaxChars(int chatMemoryToolResultMaxChars) {
        this.chatMemoryToolResultMaxChars = chatMemoryToolResultMaxChars;
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

    public DraftContinueProperties getDraftContinue() {
        return draftContinue;
    }

    public void setDraftContinue(DraftContinueProperties draftContinue) {
        this.draftContinue = draftContinue != null ? draftContinue : new DraftContinueProperties();
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

    /** 结构化 YAML 草稿自动续写（{@code ai.draft-continue.*}） */
    public static class DraftContinueProperties {

        private int maxTurnContinues = 3;
        private int maxTablesAutoContinue = 40;
        private int repairMaxChars = 8_000;
        private int repairMaxTables = 25;
        private int minDraftGrowthChars = 32;

        public int getMaxTurnContinues() {
            return maxTurnContinues;
        }

        public void setMaxTurnContinues(int maxTurnContinues) {
            this.maxTurnContinues = maxTurnContinues;
        }

        public int getMaxTablesAutoContinue() {
            return maxTablesAutoContinue;
        }

        public void setMaxTablesAutoContinue(int maxTablesAutoContinue) {
            this.maxTablesAutoContinue = maxTablesAutoContinue;
        }

        public int getRepairMaxChars() {
            return repairMaxChars;
        }

        public void setRepairMaxChars(int repairMaxChars) {
            this.repairMaxChars = repairMaxChars;
        }

        public int getRepairMaxTables() {
            return repairMaxTables;
        }

        public void setRepairMaxTables(int repairMaxTables) {
            this.repairMaxTables = repairMaxTables;
        }

        public int getMinDraftGrowthChars() {
            return minDraftGrowthChars;
        }

        public void setMinDraftGrowthChars(int minDraftGrowthChars) {
            this.minDraftGrowthChars = minDraftGrowthChars;
        }
    }

    /** 各 Agent 的 Tool Set 绑定（{@code ai.agents.<id>.tool-set-id}） */
    public static class AgentProperties {

        private String toolSetId;

        public String getToolSetId() {
            return toolSetId;
        }

        public void setToolSetId(String toolSetId) {
            this.toolSetId = toolSetId;
        }
    }
}
