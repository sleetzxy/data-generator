package com.datagenerator.web.dto;

import java.util.ArrayList;
import java.util.List;

public class PreviewOptions {

    private int limit = 10;
    private List<String> tables = new ArrayList<>();

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public List<String> getTables() {
        return tables;
    }

    public void setTables(List<String> tables) {
        this.tables = tables == null ? new ArrayList<>() : tables;
    }
}
