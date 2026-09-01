package com.datagenerator.web.dto;

import java.util.List;

/** 按配置路径聚合的运行索引：每路径最新一次运行与活跃（等待中/运行中）运行 */
public record TaskRunIndexResponse(
        List<TaskRunSummaryResponse> latestRuns,
        List<TaskRunSummaryResponse> activeRuns) {
}
