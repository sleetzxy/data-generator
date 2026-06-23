package com.datagenerator.ai.skill.runtime;

import com.datagenerator.ai.config.ChatModelFactory;
import com.datagenerator.ai.session.ChatMemoryStore;
import com.datagenerator.ai.skill.SkillCatalog;

/**
 * 单次会话内 Skill 执行所需的共享依赖。
 */
public record SkillExecutionContext(
        String provider,
        ChatModelFactory chatModelFactory,
        ChatMemoryStore chatMemoryStore,
        SkillCatalog skillCatalog) {
}
