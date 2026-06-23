package com.datagenerator.ai.port;

import java.util.List;
import java.util.Map;

public interface JobPreviewPort {

    PreviewResult preview(String jobConfigYaml, int limitPerTable, List<String> tableNames);

    record PreviewResult(String status, String duration, List<PreviewTable> tables) {}

    record PreviewTable(String name, int rowCount, List<Map<String, Object>> rows) {}
}
