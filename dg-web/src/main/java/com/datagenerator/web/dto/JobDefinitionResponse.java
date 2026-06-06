package com.datagenerator.web.dto;

public class JobDefinitionResponse {

    /** 配置文件名（不含扩展名），用于 API 路径参数 */
    private String fileName;
    /** YAML 中的 name 字段，任务描述名称 */
    private String name;
    private String path;
    private String id;
    private String content;
    private boolean readOnly;

    public JobDefinitionResponse() {
    }

    public JobDefinitionResponse(
            String fileName,
            String path,
            String id,
            String name,
            String content,
            boolean readOnly) {
        this.fileName = fileName;
        this.path = path;
        this.id = id;
        this.name = name;
        this.content = content;
        this.readOnly = readOnly;
    }

    public JobDefinitionResponse(String fileName, String path, String id, String name, boolean readOnly) {
        this(fileName, path, id, name, null, readOnly);
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

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
}
