package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.YamlConfigLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;

class TaskConfigLoadBuiltinTest {

    @Test
    void loadTaskConfig_cc3CityAcdDutysimpleSh_succeeds() {
        YamlConfigLoader loader = new YamlConfigLoader(productionLikeResolver());
        assertThatCode(() -> loader.loadTaskConfig("task-configs/cc_3_city_acd_dutysimple_sh.yaml"))
                .doesNotThrowAnyException();
    }

    private static ConfigPathResolver productionLikeResolver() {
        return ConfigPathResolver.fromSetting(
                "classpath:configs",
                TaskConfigLoadBuiltinTest.class.getClassLoader(),
                Path.of("./data/configs"));
    }
}
