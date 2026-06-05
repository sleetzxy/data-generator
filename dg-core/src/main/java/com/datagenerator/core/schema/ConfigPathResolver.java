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
import java.util.LinkedHashSet;
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
    private final Path writableOverlay;

    private ConfigPathResolver(
            Path configDir,
            ClassLoader classLoader,
            String classpathBase,
            Path writableOverlay) {
        this.configDir = configDir;
        this.classLoader = classLoader;
        this.classpathBase = classpathBase == null ? "" : classpathBase;
        this.writableOverlay = writableOverlay;
    }

    public static ConfigPathResolver forConfigDir(Path configDir) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir must not be null");
        }
        return new ConfigPathResolver(configDir, null, "", null);
    }

    public static ConfigPathResolver forClasspath(ClassLoader classLoader) {
        return forClasspath(classLoader, "");
    }

    public static ConfigPathResolver forClasspath(ClassLoader classLoader, String basePath) {
        if (classLoader == null) {
            throw new IllegalArgumentException("classLoader must not be null");
        }
        return new ConfigPathResolver(null, classLoader, normalizeClasspathBase(basePath), null);
    }

    public static ConfigPathResolver fromSetting(String configDir, ClassLoader classLoader) {
        return fromSetting(configDir, classLoader, null);
    }

    public static ConfigPathResolver fromSetting(
            String configDir, ClassLoader classLoader, Path writableOverlay) {
        if (configDir == null || configDir.isBlank()) {
            throw new IllegalArgumentException("configDir must not be blank");
        }
        ConfigPathResolver primary;
        if (configDir.startsWith("classpath:")) {
            String basePath = configDir.substring("classpath:".length()).trim();
            primary = forClasspath(classLoader, basePath);
        } else {
            primary = forConfigDir(Path.of(configDir).toAbsolutePath().normalize());
        }
        if (writableOverlay == null) {
            return primary;
        }
        return primary.withWritableOverlay(writableOverlay);
    }

    public ConfigPathResolver withWritableOverlay(Path overlayDir) {
        if (overlayDir == null) {
            return this;
        }
        return new ConfigPathResolver(
                configDir,
                classLoader,
                classpathBase,
                overlayDir.toAbsolutePath().normalize());
    }

    public Path writableOverlay() {
        return writableOverlay;
    }

    public Path resolve(String relativePath) {
        if (configDir != null) {
            return configDir.resolve(relativePath).normalize();
        }
        return Path.of(relativePath);
    }

    public Path resolveOverlay(String relativePath) {
        if (writableOverlay == null) {
            return null;
        }
        return writableOverlay.resolve(relativePath).normalize();
    }

    public List<String> listYamlBasenames(String subdirectory) {
        return listYamlRelativePaths(subdirectory).stream()
                .map(path -> {
                    int slash = path.lastIndexOf('/');
                    return slash >= 0 ? path.substring(slash + 1) : path;
                })
                .map(this::toBasename)
                .sorted()
                .distinct()
                .toList();
    }

    public List<String> listYamlRelativePaths(String subdirectory) {
        Set<String> paths = new TreeSet<>();
        if (writableOverlay != null) {
            paths.addAll(listOverlayYamlRelativePaths(subdirectory));
        }
        if (configDir != null) {
            paths.addAll(listFilesystemYamlRelativePaths(configDir.resolve(subdirectory)));
        } else {
            paths.addAll(listClasspathYamlRelativePaths(subdirectory));
        }
        return new ArrayList<>(paths);
    }

    public InputStream open(String relativePath) {
        try {
            if (writableOverlay != null) {
                Path overlayFile = writableOverlay.resolve(relativePath).normalize();
                if (overlayFile.startsWith(writableOverlay) && Files.isRegularFile(overlayFile)) {
                    return Files.newInputStream(overlayFile);
                }
            }
            if (configDir != null) {
                return Files.newInputStream(resolve(relativePath));
            }
            InputStream inputStream = classLoader.getResourceAsStream(toClasspathResource(relativePath));
            if (inputStream == null) {
                throw new ConfigLoadException(
                        "Config resource not found on classpath: " + toClasspathResource(relativePath));
            }
            return inputStream;
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to open config: " + relativePath, exception);
        }
    }

    public boolean existsOnOverlay(String relativePath) {
        if (writableOverlay == null) {
            return false;
        }
        Path overlayFile = writableOverlay.resolve(relativePath).normalize();
        return overlayFile.startsWith(writableOverlay) && Files.isRegularFile(overlayFile);
    }

    private List<String> listOverlayYamlRelativePaths(String subdirectory) {
        Path dir = writableOverlay.resolve(subdirectory).normalize();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        return listFilesystemYamlRelativePaths(dir);
    }

    private List<String> listFilesystemYamlRelativePaths(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> isYamlFile(path.getFileName().toString()))
                    .map(path -> dir.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to list config directory: " + dir, exception);
        }
    }

    private List<String> listClasspathYamlRelativePaths(String subdirectory) {
        String resourcePrefix = toClasspathResource(subdirectory);
        if (!resourcePrefix.endsWith("/")) {
            resourcePrefix = resourcePrefix + "/";
        }
        try {
            Set<String> paths = new LinkedHashSet<>();
            Enumeration<URL> roots = classLoader.getResources(resourcePrefix);
            while (roots.hasMoreElements()) {
                collectClasspathYamlRelativePaths(roots.nextElement(), resourcePrefix, paths);
            }
            return new ArrayList<>(paths);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to list classpath config directory: " + resourcePrefix, exception);
        }
    }

    private void collectClasspathYamlRelativePaths(URL rootUrl, String prefix, Set<String> paths)
            throws IOException {
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
            paths.addAll(listFilesystemYamlRelativePaths(dir));
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
                    if (entry.isDirectory() || !entry.getName().startsWith(entryPrefix)) {
                        continue;
                    }
                    String relative = entry.getName().substring(entryPrefix.length());
                    if (isYamlFile(relative)) {
                        paths.add(relative);
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
