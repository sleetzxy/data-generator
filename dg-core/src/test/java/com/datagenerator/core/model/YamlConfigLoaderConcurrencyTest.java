package com.datagenerator.core.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发加载回归测试：共享 Yaml 实例曾被并发竞态破坏解析状态（Constructor.setComposer 被覆盖） */
class YamlConfigLoaderConcurrencyTest {

    private static final List<String> FIXTURES = List.of(
            "fixtures/task-configs/ecommerce_seed.yaml",
            "fixtures/task-configs/multi_table.yaml",
            "fixtures/task-configs/inline_single.yaml",
            "fixtures/task-configs/task_level_seeds.yaml",
            "fixtures/task-configs/task_level_connections.yaml",
            "fixtures/task-configs/multi_write.yaml",
            "fixtures/task-configs/seed_link_on_keyword.yaml",
            "fixtures/task-configs/scheduled_task.yaml");

    @Test
    void loadTaskConfig_concurrentLoadsOnSharedLoader_neverFails() throws Exception {
        YamlConfigLoader loader = new YamlConfigLoader(
                ConfigPathResolver.forClasspath(getClass().getClassLoader()));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                futures.add(executor.submit(() -> {
                    for (int iteration = 0; iteration < 100; iteration++) {
                        for (String fixture : FIXTURES) {
                            TaskConfig taskConfig = loader.loadTaskConfig(fixture);
                            assertThat(taskConfig.getName()).isNotBlank();
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
