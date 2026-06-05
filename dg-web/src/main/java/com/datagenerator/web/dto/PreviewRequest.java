package com.datagenerator.web.dto;

public class PreviewRequest extends JobSubmitRequest {

    private PreviewOptions preview = new PreviewOptions();

    public PreviewOptions getPreview() {
        return preview;
    }

    public void setPreview(PreviewOptions preview) {
        this.preview = preview == null ? new PreviewOptions() : preview;
    }
}
