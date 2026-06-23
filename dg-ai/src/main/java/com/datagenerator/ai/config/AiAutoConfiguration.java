package com.datagenerator.ai.config;

import com.datagenerator.ai.port.ConnectionCatalogPort;
import com.datagenerator.ai.port.JobDefinitionPort;
import com.datagenerator.ai.port.JobExecutionPort;
import com.datagenerator.ai.port.JobPreviewPort;
import com.datagenerator.ai.port.SchemaCatalogPort;
import com.datagenerator.ai.service.AgentSessionService;
import com.datagenerator.ai.session.AgentSessionRegistry;
import com.datagenerator.ai.session.ChatMemoryStore;
import com.datagenerator.ai.session.InMemoryChatMemoryStore;
import com.datagenerator.ai.skill.SkillCatalog;
import com.datagenerator.ai.skill.SkillRegistry;
import com.datagenerator.ai.skill.runtime.SkillRuntime;
import com.datagenerator.ai.skill.runtime.SkillRuntimeRegistry;
import com.datagenerator.ai.skill.runtime.generatejob.GenerateJobSkillRuntime;
import com.datagenerator.ai.tool.generatejob.JobGeneratorTools;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(DataGeneratorWebPortConfiguration.class)
@ConditionalOnProperty(prefix = "ai", name = {"server", "enabled"}, havingValue = "true")
public class AiAutoConfiguration {

    @Bean
    AgentSessionRegistry agentSessionRegistry() {
        return new AgentSessionRegistry();
    }

    @Bean
    SkillRegistry skillRegistry() {
        SkillRegistry registry = new SkillRegistry();
        registry.loadFromClasspath();
        return registry;
    }

    @Bean
    SkillCatalog skillCatalog(SkillRegistry skillRegistry) {
        return skillRegistry;
    }

    @Bean
    ChatModelFactory chatModelFactory(AiProperties properties) {
        return new ChatModelFactory(properties);
    }

    @Bean
    JobGeneratorTools jobGeneratorTools(
            ConnectionCatalogPort connectionCatalogPort,
            JobDefinitionPort jobDefinitionPort,
            SchemaCatalogPort schemaCatalogPort,
            JobPreviewPort jobPreviewPort,
            JobExecutionPort jobExecutionPort,
            AgentSessionRegistry sessionRegistry) {
        return new JobGeneratorTools(
                connectionCatalogPort,
                jobDefinitionPort,
                schemaCatalogPort,
                jobPreviewPort,
                jobExecutionPort,
                sessionRegistry);
    }

    @Bean
    GenerateJobSkillRuntime generateJobSkillRuntime(
            JobGeneratorTools jobGeneratorTools,
            JobDefinitionPort jobDefinitionPort) {
        return new GenerateJobSkillRuntime(jobGeneratorTools, jobDefinitionPort);
    }

    @Bean
    SkillRuntimeRegistry skillRuntimeRegistry(List<SkillRuntime> runtimes) {
        return new SkillRuntimeRegistry(runtimes);
    }

    @Bean
    ChatMemoryStore chatMemoryStore(AiProperties properties) {
        return new InMemoryChatMemoryStore(properties);
    }

    @Bean
    AgentSessionService agentSessionService(
            AiProperties properties,
            SkillCatalog skillCatalog,
            SkillRuntimeRegistry skillRuntimeRegistry,
            ChatModelFactory chatModelFactory,
            ChatMemoryStore chatMemoryStore,
            AgentSessionRegistry sessionRegistry) {
        return new AgentSessionService(
                properties,
                skillCatalog,
                skillRuntimeRegistry,
                chatModelFactory,
                chatMemoryStore,
                sessionRegistry);
    }
}
