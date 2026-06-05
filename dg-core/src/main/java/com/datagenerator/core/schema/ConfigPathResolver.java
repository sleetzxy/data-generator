package com.datagenerator.core.schema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class ConfigPathResolver {

    private final Path configDir;
    private final ClassLoader classLoader;

    private ConfigPathResolver(Path configDir, ClassLoader classLoader) {
        this.configDir = configDir;
        this.classLoader = classLoader;
    }

    public static ConfigPathResolver forConfigDir(Path configDir) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir must not be null");
        }
        return new ConfigPathResolver(configDir, null);
    }

    public static ConfigPathResolver forClasspath(ClassLoader classLoader) {
        if (classLoader == null) {
            throw new IllegalArgumentException("classLoader must not be null");
        }
        return new ConfigPathResolver(null, classLoader);
    }

    public Path resolve(String relativePath) {
        if (configDir != null) {
            return configDir.resolve(relativePath).normalize();
        }
        return Path.of(relativePath);
    }

    public List<String> listYamlBasenames(String subdirectory) {
        if (configDir == null) {
            return List.of();
        }
        Path dir = configDir.resolve(subdirectory).normalize();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yaml") || name.endsWith(".yml"))
                    .map(name -> name.replaceFirst("\\.ya?ml$", ""))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to list config directory: " + dir, exception);
        }
    }

    public InputStream open(String relativePath) {
        try {
            if (configDir != null) {
                return Files.newInputStream(resolve(relativePath));
            }
            InputStream inputStream = classLoader.getResourceAsStream(relativePath);
            if (inputStream == null) {
                throw new ConfigLoadException("Config resource not found on classpath: " + relativePath);
            }
            return inputStream;
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to open config: " + relativePath, exception);
        }
    }
}
