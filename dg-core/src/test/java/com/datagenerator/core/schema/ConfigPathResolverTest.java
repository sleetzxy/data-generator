package com.datagenerator.core.schema;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPathResolverTest {

    @Test
    void fromSetting_classpathPrefix_loadsResource() throws Exception {
        ConfigPathResolver resolver = ConfigPathResolver.fromSetting(
                "classpath:", ConfigPathResolverTest.class.getClassLoader());

        try (var inputStream = resolver.open("fixtures/schemas/customer.yaml")) {
            assertThat(inputStream.readAllBytes()).isNotEmpty();
        }
    }

    @Test
    void fromSetting_classpathWithBase_loadsResource() throws Exception {
        ConfigPathResolver resolver = ConfigPathResolver.fromSetting(
                "classpath:fixtures", ConfigPathResolverTest.class.getClassLoader());

        try (var inputStream = resolver.open("schemas/customer.yaml")) {
            assertThat(inputStream.readAllBytes()).isNotEmpty();
        }
    }

    @Test
    void listYamlBasenames_classpath_returnsBasenames() {
        ConfigPathResolver resolver = ConfigPathResolver.forClasspath(
                ConfigPathResolverTest.class.getClassLoader());

        List<String> basenames = resolver.listYamlBasenames("fixtures/schemas");

        assertThat(basenames).contains("customer", "order");
    }

    @Test
    void listYamlBasenames_filesystem_returnsBasenames() throws Exception {
        Path configDir = Files.createTempDirectory("dg-config-test");
        Path schemasDir = configDir.resolve("schemas");
        Files.createDirectories(schemasDir);
        Files.writeString(schemasDir.resolve("alpha.yaml"), "table: alpha\nfields: []");
        Files.writeString(schemasDir.resolve("beta.yml"), "table: beta\nfields: []");

        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(configDir);

        assertThat(resolver.listYamlBasenames("schemas")).containsExactly("alpha", "beta");
    }

    @Test
    void existsOnClasspath_classpathResource_returnsTrue() {
        ConfigPathResolver resolver = ConfigPathResolver.forClasspath(
                ConfigPathResolverTest.class.getClassLoader());

        assertThat(resolver.existsOnClasspath("fixtures/schemas/customer.yaml")).isTrue();
        assertThat(resolver.existsOnClasspath("fixtures/schemas/missing.yaml")).isFalse();
    }

    @Test
    void writableOverlay_overridesClasspathResource() throws Exception {
        Path overlay = Files.createTempDirectory("dg-overlay-test");
        Path primaryDir = Files.createTempDirectory("dg-primary-test");
        Files.createDirectories(primaryDir.resolve("jobs"));
        Files.writeString(primaryDir.resolve("jobs/demo.yaml"), "id: primary\nname: primary\ntables: []");
        Files.createDirectories(overlay.resolve("jobs"));
        Files.writeString(overlay.resolve("jobs/demo.yaml"), "id: overlay\nname: overlay\ntables: []");

        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlay);

        try (var inputStream = resolver.open("jobs/demo.yaml")) {
            assertThat(new String(inputStream.readAllBytes())).contains("overlay");
        }
    }
}
