package com.datagenerator.ai.config;

import com.datagenerator.ai.config.AiProperties.EmbeddingProperties;
import com.datagenerator.ai.config.AiProperties.ModelProperties;
import com.datagenerator.ai.embedding.SimpleEmbeddingModel;
import com.datagenerator.ai.prompt.SystemPrompt;
import com.datagenerator.ai.tool.ConfigTools;
import com.datagenerator.ai.tool.KnowledgeTools;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import java.time.Duration;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope HarnessAgent 装配配置。
 * 使用 agentscope-harness 的 HarnessAgent 作为 Agent 运行时。
 */
@Configuration
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiAutoConfiguration.class);

    // ==================== Model ====================

    @Bean
    Model chatModel(AiProperties props) {
        ModelProperties m = props.getModel();
        log.info("初始化 Model: provider={}, model={}", m.provider(), m.modelName());

        GenerateOptions.Builder optsBuilder = GenerateOptions.builder();
        if (m.temperature() != null) {
            optsBuilder.temperature(m.temperature());
        }
        if (m.maxTokens() != null) {
            optsBuilder.maxTokens(m.maxTokens());
        }
        if (m.timeout() != null) {
            optsBuilder.executionConfig(
                    ExecutionConfig.builder().timeout(m.timeout()).build());
        }
        GenerateOptions opts = optsBuilder.build();

        return switch (m.provider()) {
            case "openai-compatible" -> OpenAIChatModel.builder()
                    .baseUrl(m.baseUrl())
                    .apiKey(m.apiKey())
                    .modelName(m.modelName())
                    .generateOptions(opts)
                    .build();
            case "ollama" -> {
                String baseUrl = m.baseUrl() != null ? m.baseUrl() : "http://localhost:11434";
                yield OpenAIChatModel.builder()
                        .baseUrl(baseUrl)
                        .modelName(m.modelName())
                        .generateOptions(opts)
                        .build();
            }
            default -> throw new IllegalStateException("不支持的模型 provider: " + m.provider());
        };
    }

    // ==================== Embedding & RAG ====================

    @Bean
    EmbeddingModel embeddingModel(AiProperties props) {
        EmbeddingProperties e = props.getEmbedding();
        log.info("初始化 EmbeddingModel: provider={}, model={}, dimensions={}",
                e.provider(), e.modelName(), e.dimensions());

        return switch (e.provider()) {
            case "ollama" -> OllamaTextEmbedding.builder()
                    .baseUrl(e.baseUrl())
                    .modelName(e.modelName())
                    .dimensions(e.dimensions())
                    .build();
            case "openai-compatible" -> new SimpleEmbeddingModel(
                    e.baseUrl(), e.apiKey(), e.modelName(), e.dimensions());
            default -> throw new IllegalStateException("不支持的 embedding provider: " + e.provider());
        };
    }

    @Bean
    VDBStoreBase embeddingStore(AiProperties props) {
        int dims = props.getEmbedding().dimensions();
        log.info("初始化 InMemoryStore: dimensions={}", dims);
        return InMemoryStore.builder().dimensions(dims).build();
    }

    @Bean
    Knowledge knowledge(EmbeddingModel embeddingModel, VDBStoreBase embeddingStore) {
        log.info("初始化 SimpleKnowledge");
        return SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    // ==================== Toolkit ====================

    @Bean
    Toolkit agentToolkit(ConfigTools configTools, KnowledgeTools knowledgeTools) {
        log.info("初始化 Toolkit: ConfigTools + KnowledgeTools");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(configTools);
        toolkit.registerTool(knowledgeTools);
        return toolkit;
    }

    // ==================== AgentStateStore ====================

    @Bean
    AgentStateStore agentStateStore(AiProperties props) {
        var session = props.getSession();
        // 默认使用内存存储，避免 HITL 确认状态残留
        if (session == null || session.store() == null || session.store().isBlank()) {
            log.info("初始化 InMemoryAgentStateStore（默认）");
            return new InMemoryAgentStateStore();
        }
        return switch (session.store()) {
            case "file" -> {
                String path = session.file().path();
                log.info("初始化 JsonFileAgentStateStore: path={}", path);
                yield new JsonFileAgentStateStore(Paths.get(path));
            }
            case "memory" -> {
                log.info("初始化 InMemoryAgentStateStore");
                yield new InMemoryAgentStateStore();
            }
            default -> {
                log.warn("未知 session.store={}，回退 InMemoryAgentStateStore", session.store());
                yield new InMemoryAgentStateStore();
            }
        };
    }

    // ==================== HarnessAgent ====================

    @Bean
    HarnessAgent harnessAgent(Model model, Toolkit toolkit,
            AgentStateStore stateStore, AiProperties props) {
        var agent = props.getAgent();
        var workspace = props.getWorkspace();
        var memory = props.getMemory();
        log.info("初始化 HarnessAgent: name={}, maxIters={}, memoryFlushMode={}",
                agent.name(), agent.maxIterations(), memory.flushMode());

        // 构建 MemoryConfig，默认使用 throttled 模式避免每次对话都立即 offload
        MemoryConfig memoryConfig = buildMemoryConfig(memory);
        log.info("Memory 配置: flushMode={}, flushMinGap={}",
                memory.flushMode(), memory.flushMinGap());

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agent.name())
                .sysPrompt(SystemPrompt.CONTENT)
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .workspace(Paths.get(workspace.root()))
                .maxIters(agent.maxIterations())
                .memory(memoryConfig);

        return builder.build();
    }

    /**
     * 根据配置构建 MemoryConfig，控制 memory flush 和 conversation offload 行为。
     *
     * <p>flushMode 支持三种模式：
     * <ul>
     *   <li>{@code throttled}（默认）：节流模式，两次 flush 之间至少间隔 flushMinGap</li>
     *   <li>{@code always}：每次 Agent 响应后立即 flush + offload</li>
     *   <li>{@code never}：禁用自动 memory flush</li>
     * </ul>
     */
    private MemoryConfig buildMemoryConfig(AiProperties.MemoryProperties memory) {
        MemoryConfig.FlushTrigger trigger = switch (memory.flushMode()) {
            case "always" -> MemoryConfig.FlushTrigger.always();
            case "never" -> MemoryConfig.FlushTrigger.never();
            default -> {
                Duration gap = memory.flushMinGap() != null
                        ? memory.flushMinGap() : Duration.ofMinutes(5);
                yield MemoryConfig.FlushTrigger.throttled(gap);
            }
        };
        return MemoryConfig.builder()
                .flushTrigger(trigger)
                .build();
    }
}
