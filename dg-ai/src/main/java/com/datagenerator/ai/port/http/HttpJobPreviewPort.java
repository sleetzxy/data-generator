package com.datagenerator.ai.port.http;

import com.datagenerator.ai.port.JobPreviewPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;

public class HttpJobPreviewPort implements JobPreviewPort {

    private static final String PREVIEW_PATH = "/api/v1/preview";

    private final HttpServiceClient webClient;

    public HttpJobPreviewPort(HttpServiceClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public PreviewResult preview(String jobConfigYaml, int limitPerTable, List<String> tableNames) {
        PreviewRequest request = new PreviewRequest(
                jobConfigYaml,
                new PreviewOptions(limitPerTable, tableNames == null ? List.of() : tableNames));
        PreviewDto response = webClient.post(PREVIEW_PATH, request, new TypeReference<>() {});
        List<PreviewTable> tables = response.tables() == null
                ? List.of()
                : response.tables().stream()
                        .map(table -> new PreviewTable(
                                table.tableName(),
                                table.rows() == null ? 0 : table.rows().size(),
                                table.rows()))
                        .toList();
        return new PreviewResult(
                response.status() == null ? "UNKNOWN" : response.status(),
                response.duration(),
                tables);
    }

    private record PreviewRequest(String jobConfig, PreviewOptions preview) {}

    private record PreviewOptions(int limit, List<String> tables) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PreviewDto(String status, String duration, List<PreviewTableDto> tables) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PreviewTableDto(String tableName, List<Map<String, Object>> rows) {}
}
