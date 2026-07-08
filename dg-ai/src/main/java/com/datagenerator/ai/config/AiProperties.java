package com.datagenerator.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private boolean enabled;
    private ModelProperties model = new ModelProperties("openai-compatible", null, null, "qwen2.5:7b", 0.3, 8192, Duration.ofSeconds(120));
    private AgentProperties agent = new AgentProperties("Data Generator 配置顾问", 30);
    private SessionProperties session = new SessionProperties("file", new SessionProperties.FileStore("./data/agent-sessions"), Duration.ofHours(24));
    private WorkspaceProperties workspace = new WorkspaceProperties("./data/agent-workspaces");
    private MemoryProperties memory = new MemoryProperties("throttled", Duration.ofMinutes(5));
    private EmbeddingProperties embedding = new EmbeddingProperties("ollama", "http://localhost:11434", null, "nomic-embed-text", 768, "./data/knowledge-chunks");
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
    public MemoryProperties getMemory() { return memory; }
    public void setMemory(MemoryProperties memory) { this.memory = memory; }
    public EmbeddingProperties getEmbedding() { return embedding; }
    public void setEmbedding(EmbeddingProperties embedding) { this.embedding = embedding; }
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

    public record MemoryProperties(
        String flushMode,
        Duration flushMinGap
    ) {}

    public record EmbeddingProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String modelName,
        int dimensions,
        String storagePath
    ) {}

    public record DgWebProperties(String baseUrl, String authToken) {}
}
