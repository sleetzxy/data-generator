package com.datagenerator.web.dto;

/** 任务运行列表查询条件：status/configPath 精确匹配，from/to 为提交时间范围（ISO，含边界） */
public record TaskRunListFilter(String status, String configPath, String from, String to) {
}
