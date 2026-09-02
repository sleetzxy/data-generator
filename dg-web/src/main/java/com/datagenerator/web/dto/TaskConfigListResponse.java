package com.datagenerator.web.dto;

import java.util.List;

/** 任务配置列表响应：任务条目 + 分页元数据 */
public record TaskConfigListResponse(
        List<TaskConfigResponse> items,
        long total,
        int page,
        int size) {
}
