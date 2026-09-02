package com.datagenerator.web.dto;

public class TaskConfigResponse {

    /** 配置文件名（不含扩展名），用于 API 路径参数 */
    private String fileName;
    /** 任务显示名称（来自主表 display_name） */
    private String name;
    private String path;
    private String id;
    private String content;
    private TaskScheduleResponse schedule;
    /** 任务创建时间（ISO-8601） */
    private String createdAt;

    public TaskConfigResponse() {
    }

    public TaskConfigResponse(
            String fileName,
            String path,
            String id,
            String name,
            String content) {
        this.fileName = fileName;
        this.path = path;
        this.id = id;
        this.name = name;
        this.content = content;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public TaskScheduleResponse getSchedule() {
        return schedule;
    }

    public void setSchedule(TaskScheduleResponse schedule) {
        this.schedule = schedule;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
