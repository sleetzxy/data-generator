package com.datagenerator.web.dto;

public class JobDefinitionResponse {

    private String name;
    private String path;
    private String content;
    private boolean readOnly;

    public JobDefinitionResponse() {
    }

    public JobDefinitionResponse(String name, String path, String content, boolean readOnly) {
        this.name = name;
        this.path = path;
        this.content = content;
        this.readOnly = readOnly;
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
