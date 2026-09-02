package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.YamlConfigLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/** 验证受控测试 fixture 的示例任务配置可通过完整加载校验 */
class TaskConfigLoadFixtureTest {

    @Test
    void loadTaskConfig_fixtureSample_succeeds() {
        YamlConfigLoader loader = new YamlConfigLoader(fixtureResolver());
        assertThatCode(() -> loader.loadTaskConfig("task-configs/sample.yaml"))
                .doesNotThrowAnyException();
    }

    private static ConfigPathResolver fixtureResolver() {
        return ConfigPathResolver.forClasspath(
                TaskConfigLoadFixtureTest.class.getClassLoader(), "fixtures");
    }
}
