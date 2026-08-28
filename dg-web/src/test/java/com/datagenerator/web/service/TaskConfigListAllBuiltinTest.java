package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.YamlConfigLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class TaskConfigListAllBuiltinTest {

    private static final String TASK_CONFIGS_DIR = "task-configs";

    @Test
    void listAllTopLevelBuiltinConfigs_loadsSuccessfully() {
        ConfigPathResolver resolver = productionLikeResolver();
        YamlConfigLoader loader = new YamlConfigLoader(resolver);
        List<String> failures = new ArrayList<>();
        for (String relativePath : resolver.listYamlRelativePaths(TASK_CONFIGS_DIR)) {
            if (relativePath.contains("/") && resolver.existsOnClasspath(TASK_CONFIGS_DIR + "/" + relativePath)) {
                continue;
            }
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

    private static ConfigPathResolver productionLikeResolver() {
        return ConfigPathResolver.fromSetting(
                "classpath:configs",
                TaskConfigListAllBuiltinTest.class.getClassLoader(),
                Path.of("./data/configs"));
    }
}
