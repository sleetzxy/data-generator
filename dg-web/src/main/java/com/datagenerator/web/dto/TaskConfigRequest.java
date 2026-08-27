package com.datagenerator.web.dto;

public class TaskConfigRequest {

    /** 配置文件名（ASCII，新建时可选；未指定时使用自动生成的 job id）。 */
    private String name;
    /** 任务显示名称，写入 YAML 的 name 字段。 */
    private String displayName;
    private String content;
    private TaskScheduleRequest schedule;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public TaskScheduleRequest getSchedule() {
        return schedule;
    }

    public void setSchedule(TaskScheduleRequest schedule) {
        this.schedule = schedule;
    }
}
