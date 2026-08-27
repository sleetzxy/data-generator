package com.datagenerator.web.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行日志目录迁移：旧版默认 {@code ./data/job-logs} → {@code ./data/task-run-logs}。
 */
public final class TaskRunLogDirMigrator {

    private static final Logger log = LoggerFactory.getLogger(TaskRunLogDirMigrator.class);
    static final String LEGACY_DIR_NAME = "job-logs";

    private TaskRunLogDirMigrator() {
    }

    static Path migrateLegacyLogDir(Path configuredLogDir) throws IOException {
        Path normalized = configuredLogDir.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            return normalized;
        }
        Path legacyDir = normalized.getParent().resolve(LEGACY_DIR_NAME);
        if (Files.exists(legacyDir)) {
            Files.move(legacyDir, normalized);
            log.info("已迁移运行日志目录: {} -> {}", legacyDir, normalized);
        }
        return normalized;
    }
}
