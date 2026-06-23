package com.datagenerator.ai.dto;

public class ProviderInfo {

    private final String id;
    private final String label;
    private final String model;
    private final boolean defaultProvider;

    public ProviderInfo(String id, String label, String model, boolean defaultProvider) {
        this.id = id;
        this.label = label;
        this.model = model;
        this.defaultProvider = defaultProvider;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getModel() {
        return model;
    }

    public boolean isDefaultProvider() {
        return defaultProvider;
    }
}
