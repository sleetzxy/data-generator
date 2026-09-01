package com.datagenerator.web.dto;

import java.util.List;

/** 运行概览统计响应：状态计数、累计写入、Top 配置排行与近 14 天每日趋势 */
public record TaskRunStatsResponse(
        long totalRuns,
        long running,
        long pending,
        long completed,
        long failed,
        long cancelled,
        long totalWritten,
        List<ConfigVolumeStat> topConfigs,
        List<DailyRunStat> daily) {
}
