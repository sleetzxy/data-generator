package com.datagenerator.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JobDefinitionResponse {

    /** 配置文件名（不含扩展名），用于 API 路径参数 */
    private String fileName;
    /** YAML 中的 name 字段，任务描述名称 */
    private String name;
    private String path;
    private String id;
    private String content;
    /** 是否内置（来自 classpath / 主配置目录） */
    private boolean builtin;
    /** 是否只读（内置任务不可编辑/删除） */
    @JsonProperty("readOnly")
    private boolean readOnly;

    public JobDefinitionResponse() {
    }

    public JobDefinitionResponse(
            String fileName,
            String path,
            String id,
            String name,
            String content,
            boolean builtin,
            boolean readOnly) {
        this.fileName = fileName;
        this.path = path;
        this.id = id;
        this.name = name;
        this.content = content;
        this.builtin = builtin;
        this.readOnly = readOnly;
    }

    public JobDefinitionResponse(String fileName, String path, String id, String name, boolean builtin) {
        this(fileName, path, id, name, null, builtin, builtin);
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

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
}
