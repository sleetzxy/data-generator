package com.datagenerator.ai.skill.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.datagenerator.ai.port.JobDefinitionPort;
import com.datagenerator.ai.skill.runtime.generatejob.GenerateJobSkillRuntime;
import com.datagenerator.ai.tool.generatejob.JobGeneratorTools;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillRuntimeRegistryTest {

    @Test
    void require_unknownSkill_throws() {
        JobGeneratorTools tools = mock(JobGeneratorTools.class);
        JobDefinitionPort jobDefinitions = mock(JobDefinitionPort.class);
        SkillRuntimeRegistry registry =
                new SkillRuntimeRegistry(List.of(new GenerateJobSkillRuntime(tools, jobDefinitions)));

        assertThatThrownBy(() -> registry.require("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void hasRuntime_registeredSkill_returnsTrue() {
        JobGeneratorTools tools = mock(JobGeneratorTools.class);
        JobDefinitionPort jobDefinitions = mock(JobDefinitionPort.class);
        SkillRuntimeRegistry registry =
                new SkillRuntimeRegistry(List.of(new GenerateJobSkillRuntime(tools, jobDefinitions)));

        org.assertj.core.api.Assertions.assertThat(registry.hasRuntime(GenerateJobSkillRuntime.SKILL_ID))
                .isTrue();
    }
}
