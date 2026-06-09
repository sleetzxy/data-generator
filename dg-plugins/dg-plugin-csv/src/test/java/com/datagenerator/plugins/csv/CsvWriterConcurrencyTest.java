package com.datagenerator.plugins.csv;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriterConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterConcurrencyTest {

    @Test
    void csvWriter_concurrentJobsWithMultipleTables_completesWithoutError() throws Exception {
        Path outputDir = Files.createTempDirectory("csv-concurrent");
        try {
            WriterConfig config = new WriterConfig(
                    "csv", null, "insert", null, outputDir.toString(), null, null, null);

            try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int job = 0; job < 4; job++) {
                    int jobId = job;
                    futures.add(pool.submit(() -> runMultiTableJob(config, jobId)));
                }
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            }

            for (int job = 0; job < 4; job++) {
                assertThat(outputDir.resolve("table_a_" + job + ".csv")).exists();
                assertThat(outputDir.resolve("table_b_" + job + ".csv")).exists();
            }
        } finally {
            deleteRecursively(outputDir);
        }
    }

    private static void runMultiTableJob(WriterConfig config, int jobId) {
        CsvWriter writer = new CsvWriter();
        writer.init(config);
        for (int i = 0; i < 50; i++) {
            writer.write(new Batch(
                    "table_a_" + jobId,
                    List.of(new DataRow(Map.of("id", String.valueOf(i), "job", String.valueOf(jobId))))));
            writer.write(new Batch(
                    "table_b_" + jobId,
                    List.of(new DataRow(Map.of("id", String.valueOf(i), "job", String.valueOf(jobId))))));
        }
        writer.close();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 测试清理失败可忽略
                    }
                });
    }
}
