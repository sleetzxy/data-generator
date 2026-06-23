package com.datagenerator.ai.skill.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SkillRuntimeRegistry {

    private final Map<String, SkillRuntime> runtimes = new LinkedHashMap<>();

    public SkillRuntimeRegistry(List<SkillRuntime> runtimes) {
        for (SkillRuntime runtime : runtimes) {
            SkillRuntime previous = this.runtimes.put(runtime.skillId(), runtime);
            if (previous != null) {
                throw new IllegalStateException("Duplicate SkillRuntime for skill: " + runtime.skillId());
            }
        }
    }

    public SkillRuntime require(String skillId) {
        SkillRuntime runtime = runtimes.get(skillId);
        if (runtime == null) {
            throw new IllegalArgumentException("No runtime registered for skill: " + skillId);
        }
        return runtime;
    }

    public boolean hasRuntime(String skillId) {
        return runtimes.containsKey(skillId);
    }
}
