package com.datagenerator.web.dto;

/**
 * 单个任务配置的数据量统计：任务显示名（任务已删除时为 null）、运行次数与累计写入行数。
 * displayName 由服务层按 config_path 关联任务主表补全，仓储层返回时恒为 null。
 */
public record ConfigVolumeStat(
        String configPath, String displayName, long runCount, long writtenRows) {
}
