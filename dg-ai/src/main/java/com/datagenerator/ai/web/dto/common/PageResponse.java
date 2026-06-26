package com.datagenerator.ai.web.dto.common;

import java.util.List;

/** 分页响应封装。 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public PageResponse {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
