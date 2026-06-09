package com.datagenerator.core.schema;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SeedDefinition {

    private String name;
    private SeedLinkDefinition link;
    private Map<String, Object> reader = new HashMap<>();
    private String reference;
    private Map<String, Object> template = new HashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SeedLinkDefinition getLink() {
        return link;
    }

    public void setLink(SeedLinkDefinition link) {
        this.link = link;
    }

    public Map<String, Object> getReader() {
        return Collections.unmodifiableMap(reader);
    }

    public void setReader(Map<String, Object> reader) {
        this.reader = reader == null ? new HashMap<>() : new HashMap<>(reader);
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public Map<String, Object> getTemplate() {
        return Collections.unmodifiableMap(template);
    }

    public void setTemplate(Map<String, Object> template) {
        this.template = template == null ? new HashMap<>() : new HashMap<>(template);
    }

    public boolean isRoot() {
        return link == null;
    }
}
