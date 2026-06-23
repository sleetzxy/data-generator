package com.datagenerator.ai.skill;



import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



import com.datagenerator.ai.dto.SkillInfo;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;



class SkillRegistryTest {



    private SkillRegistry registry;



    @BeforeEach

    void setUp() {

        registry = new SkillRegistry();

        registry.loadFromClasspath();

    }



    @Test

    void listSkills_findsGenerateJob() {

        assertThat(registry.list())

                .extracting(SkillInfo::getId)

                .contains("generate-job");

    }



    @Test

    void buildSystemPrompt_includesOverlay() {

        String prompt = registry.buildSystemPrompt("generate-job");



        assertThat(prompt).contains("禁止臆造");

        assertThat(prompt).contains("validateJobYaml");

        assertThat(prompt).contains("<!-- dg-artifact:yaml -->");

    }



    @Test

    void buildSystemPrompt_unknownSkill_throws() {

        assertThatThrownBy(() -> registry.buildSystemPrompt("unknown"))

                .isInstanceOf(IllegalArgumentException.class)

                .hasMessageContaining("Unknown skill");

    }

}

