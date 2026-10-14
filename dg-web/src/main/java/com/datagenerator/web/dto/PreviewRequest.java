package com.datagenerator.web.dto;

public class PreviewRequest extends TaskRunSubmitRequest {

    /** 未落盘原始 YAML；非空时优先于此字段预览，不写文件、不查 tasks 表。 */
    private String content;

    private PreviewOptions preview = new PreviewOptions();

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public PreviewOptions getPreview() {
        return preview;
    }

    public void setPreview(PreviewOptions preview) {
        this.preview = preview == null ? new PreviewOptions() : preview;
    }
}
