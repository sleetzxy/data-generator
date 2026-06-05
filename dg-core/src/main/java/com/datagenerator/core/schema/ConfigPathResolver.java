package com.datagenerator.core.schema;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class ConfigPathResolver {

    private final Path configDir;
    private final ClassLoader classLoader;
    private final String classpathBase;

    private ConfigPathResolver(Path configDir, ClassLoader classLoader, String classpathBase) {
        this.configDir = configDir;
        this.classLoader = classLoader;
        this.classpathBase = classpathBase == null ? "" : classpathBase;
    }

    public static ConfigPathResolver forConfigDir(Path configDir) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir must not be null");
        }
        return new ConfigPathResolver(configDir, null, "");
    }

    public static ConfigPathResolver forClasspath(ClassLoader classLoader) {
        return forClasspath(classLoader, "");
    }

    public static ConfigPathResolver forClasspath(ClassLoader classLoader, String basePath) {
        if (classLoader == null) {
            throw new IllegalArgumentException("classLoader must not be null");
        }
        return new ConfigPathResolver(null, classLoader, normalizeClasspathBase(basePath));
    }

    public static ConfigPathResolver fromSetting(String configDir, ClassLoader classLoader) {
        if (configDir == null || configDir.isBlank()) {
            throw new IllegalArgumentException("configDir must not be blank");
        }
        if (configDir.startsWith("classpath:")) {
            String basePath = configDir.substring("classpath:".length()).trim();
            return forClasspath(classLoader, basePath);
        }
        return forConfigDir(Path.of(configDir).toAbsolutePath().normalize());
    }

    public Path resolve(String relativePath) {
        if (configDir != null) {
            return configDir.resolve(relativePath).normalize();
        }
        return Path.of(relativePath);
    }

    public List<String> listYamlBasenames(String subdirectory) {
        if (configDir != null) {
            return listFilesystemYamlBasenames(subdirectory);
        }
        return listClasspathYamlBasenames(subdirectory);
    }

    public InputStream open(String relativePath) {
        try {
            if (configDir != null) {
                return Files.newInputStream(resolve(relativePath));
            }
            InputStream inputStream = classLoader.getResourceAsStream(toClasspathResource(relativePath));
            if (inputStream == null) {
                throw new ConfigLoadException("Config resource not found on classpath: " + toClasspathResource(relativePath));
            }
            return inputStream;
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to open config: " + relativePath, exception);
        }
    }

    private List<String> listFilesystemYamlBasenames(String subdirectory) {
        Path dir = configDir.resolve(subdirectory).normalize();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(this::isYamlFile)
                    .map(this::toBasename)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to list config directory: " + dir, exception);
        }
    }

    private List<String> listClasspathYamlBasenames(String subdirectory) {
        String resourcePrefix = toClasspathResource(subdirectory);
        if (!resourcePrefix.endsWith("/")) {
            resourcePrefix = resourcePrefix + "/";
        }
        try {
            Set<String> basenames = new TreeSet<>();
            Enumeration<URL> roots = classLoader.getResources(resourcePrefix);
            while (roots.hasMoreElements()) {
                collectYamlBasenames(roots.nextElement(), resourcePrefix, basenames);
            }
            return new ArrayList<>(basenames);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to list classpath config directory: " + resourcePrefix, exception);
        }
    }

    private void collectYamlBasenames(URL rootUrl, String prefix, Set<String> basenames) throws IOException {
        if ("file".equals(rootUrl.getProtocol())) {
            Path dir;
            try {
                dir = Path.of(rootUrl.toURI());
            } catch (URISyntaxException exception) {
                throw new ConfigLoadException("Failed to resolve classpath config directory: " + rootUrl, exception);
            }
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> paths = Files.list(dir)) {
                paths.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(this::isYamlFile)
                        .map(this::toBasename)
                        .forEach(basenames::add);
            }
            return;
        }
        if ("jar".equals(rootUrl.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) rootUrl.openConnection();
            try (JarFile jarFile = connection.getJarFile()) {
                String entryPrefix = connection.getEntryName();
                if (entryPrefix == null) {
                    return;
                }
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (!name.startsWith(entryPrefix) || !isYamlFile(name)) {
                        continue;
                    }
                    String relative = name.substring(entryPrefix.length());
                    if (!relative.contains("/")) {
                        basenames.add(toBasename(relative));
                    }
                }
            }
        }
    }

    private String toClasspathResource(String relativePath) {
        if (classpathBase.isEmpty()) {
            return relativePath;
        }
        return classpathBase + "/" + relativePath;
    }

    private static String normalizeClasspathBase(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "";
        }
        String normalized = basePath.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isYamlFile(String name) {
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private String toBasename(String filename) {
        return filename.replaceFirst("\\.ya?ml$", "");
    }
}
