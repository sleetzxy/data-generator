package com.datagenerator.web.dto;

import java.util.List;

/**
 * 远程表字段列表。
 */
public record RemoteColumnListResponse(List<Column> columns) {

    public record Column(
            String name, String typeName, boolean nullable, boolean primaryKey) {}
}
