package com.datagenerator.ai.skill;

import com.datagenerator.ai.dto.SkillInfo;
import java.util.List;

/**
 * Skill 注册表对外契约：发现、列举与拼装 system prompt。
 */
public interface SkillCatalog {

    List<SkillInfo> list();

    SkillDefinition get(String skillId);

    String buildSystemPrompt(String skillId);
}
