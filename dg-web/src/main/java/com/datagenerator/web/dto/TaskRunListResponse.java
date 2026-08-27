package com.datagenerator.web.dto;

import java.util.List;

public class TaskRunListResponse {

    private List<TaskRunSummaryResponse> items;
    private long total;
    private int page;
    private int size;

    public TaskRunListResponse() {
    }

    public TaskRunListResponse(List<TaskRunSummaryResponse> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public List<TaskRunSummaryResponse> getItems() {
        return items;
    }

    public void setItems(List<TaskRunSummaryResponse> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
