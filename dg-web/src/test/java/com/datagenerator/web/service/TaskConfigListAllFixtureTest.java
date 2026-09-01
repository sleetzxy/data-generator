package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.YamlConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证受控 fixture 目录下全部任务配置均可加载，不依赖部署方本地数据 */
class TaskConfigListAllFixtureTest {

    private static final String TASK_CONFIGS_DIR = "task-configs";

    @Test
    void listAllFixtureConfigs_loadsSuccessfully() {
        ConfigPathResolver resolver = fixtureResolver();
        YamlConfigLoader loader = new YamlConfigLoader(resolver);
        List<String> relativePaths = resolver.listYamlRelativePaths(TASK_CONFIGS_DIR);
        assertThat(relativePaths).contains("sample.yaml");

        List<String> failures = new ArrayList<>();
        for (String relativePath : relativePaths) {
            String configPath = TASK_CONFIGS_DIR + "/" + relativePath;
            try {
                loader.loadTaskConfig(configPath);
            } catch (ConfigLoadException exception) {
                failures.add(configPath + ": " + exception.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("Failed configs:\n" + String.join("\n", failures));
        }
    }

    private static ConfigPathResolver fixtureResolver() {
        return ConfigPathResolver.forClasspath(
                TaskConfigListAllFixtureTest.class.getClassLoader(), "fixtures");
    }
}
