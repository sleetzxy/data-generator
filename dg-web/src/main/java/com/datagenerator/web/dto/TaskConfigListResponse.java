package com.datagenerator.web.dto;

import java.util.List;

/** 任务配置列表响应：正常项 + 加载失败被跳过的配置 + 分页元数据（未分页时 total 为全部条目数） */
public record TaskConfigListResponse(
        List<TaskConfigResponse> items,
        List<TaskConfigSkipInfo> skipped,
        long total,
        int page,
        int size) {
}
