package com.datagenerator.ai.skill;

public class SkillDefinition {

    private final String id;
    private final String name;
    private final String description;
    private final String skillBody;
    private final String referenceBody;
    private final String overlayBody;

    public SkillDefinition(
            String id,
            String name,
            String description,
            String skillBody,
            String referenceBody,
            String overlayBody) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.skillBody = skillBody;
        this.referenceBody = referenceBody;
        this.overlayBody = overlayBody;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getSkillBody() {
        return skillBody;
    }

    public String getReferenceBody() {
        return referenceBody;
    }

    public String getOverlayBody() {
        return overlayBody;
    }
}
