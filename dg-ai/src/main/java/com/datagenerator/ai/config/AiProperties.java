package com.datagenerator.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private boolean enabled;
    private ModelProperties model = new ModelProperties("openai-compatible", null, null, "qwen2.5:7b", 0.3, 4096, Duration.ofSeconds(120));
    private AgentProperties agent = new AgentProperties("Data Generator 配置顾问", 15);
    private SessionProperties session = new SessionProperties("file", new SessionProperties.FileStore("./data/agent-sessions"), Duration.ofHours(24));
    private WorkspaceProperties workspace = new WorkspaceProperties("./data/agent-workspaces");
    private DgWebProperties dgWeb = new DgWebProperties("", "");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public ModelProperties getModel() { return model; }
    public void setModel(ModelProperties model) { this.model = model; }
    public AgentProperties getAgent() { return agent; }
    public void setAgent(AgentProperties agent) { this.agent = agent; }
    public SessionProperties getSession() { return session; }
    public void setSession(SessionProperties session) { this.session = session; }
    public WorkspaceProperties getWorkspace() { return workspace; }
    public void setWorkspace(WorkspaceProperties workspace) { this.workspace = workspace; }
    public DgWebProperties getDgWeb() { return dgWeb; }
    public void setDgWeb(DgWebProperties dgWeb) { this.dgWeb = dgWeb; }

    // ========== 内嵌 record ==========

    public record ModelProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String modelName,
        Double temperature,
        Integer maxTokens,
        Duration timeout
    ) {}

    public record AgentProperties(
        String name,
        int maxIterations
    ) {}

    public record SessionProperties(
        String store,
        FileStore file,
        Duration ttl
    ) {
        public record FileStore(String path) {}
    }

    public record WorkspaceProperties(String root) {}

    public record DgWebProperties(String baseUrl, String authToken) {}
}
