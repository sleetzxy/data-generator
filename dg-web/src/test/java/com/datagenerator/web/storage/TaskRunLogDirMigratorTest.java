package com.datagenerator.web.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunLogDirMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    void migrateLegacyLogDir_whenLegacyExists_renamesToConfiguredPath() throws Exception {
        Path legacyDir = tempDir.resolve(TaskRunLogDirMigrator.LEGACY_DIR_NAME);
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("job-old.log"), "legacy");

        Path targetDir = tempDir.resolve("task-run-logs");
        Path resolved = TaskRunLogDirMigrator.migrateLegacyLogDir(targetDir);

        assertThat(resolved).isEqualTo(targetDir.toAbsolutePath().normalize());
        assertThat(Files.exists(targetDir)).isTrue();
        assertThat(Files.exists(legacyDir)).isFalse();
        assertThat(Files.readString(targetDir.resolve("job-old.log"))).isEqualTo("legacy");
    }

    @Test
    void migrateLegacyLogDir_whenTargetAlreadyExists_keepsTarget() throws Exception {
        Path targetDir = tempDir.resolve("task-run-logs");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("task-run-new.log"), "new");

        Path legacyDir = tempDir.resolve(TaskRunLogDirMigrator.LEGACY_DIR_NAME);
        Files.createDirectories(legacyDir);

        TaskRunLogDirMigrator.migrateLegacyLogDir(targetDir);

        assertThat(Files.exists(targetDir)).isTrue();
        assertThat(Files.exists(legacyDir)).isTrue();
        assertThat(Files.readString(targetDir.resolve("task-run-new.log"))).isEqualTo("new");
    }
}
