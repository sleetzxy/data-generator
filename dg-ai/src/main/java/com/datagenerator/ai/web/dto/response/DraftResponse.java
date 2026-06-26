package com.datagenerator.ai.web.dto.response;

public class DraftResponse {

    private String draftYaml;

    public DraftResponse() {
    }

    public DraftResponse(String draftYaml) {
        this.draftYaml = draftYaml;
    }

    public String getDraftYaml() {
        return draftYaml;
    }

    public void setDraftYaml(String draftYaml) {
        this.draftYaml = draftYaml;
    }
}
