package com.datagenerator.web.dto;

/** 单个任务配置的数据量统计：运行次数与累计写入行数 */
public record ConfigVolumeStat(String configPath, long runCount, long writtenRows) {
}
