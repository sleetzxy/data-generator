package com.datagenerator.ai.skill;

import com.datagenerator.ai.dto.SkillInfo;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkillRegistry implements SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final String SKILLS_ROOT = "skills/";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String REFERENCE_FILE = "reference.md";
    private static final String OVERLAY_FILE = "overlay.md";

    private final ClassLoader classLoader;
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    public SkillRegistry() {
        this(SkillRegistry.class.getClassLoader());
    }

    SkillRegistry(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void loadFromClasspath() {
        skills.clear();
        try {
            for (String skillId : discoverSkillIds()) {
                loadSkill(skillId);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load skills from classpath", e);
        }
        log.info("Loaded {} skill(s) from classpath", skills.size());
    }

    @Override
    public List<SkillInfo> list() {
        return skills.values().stream()
                .map(skill -> new SkillInfo(skill.getId(), skill.getName(), skill.getDescription()))
                .toList();
    }

    @Override
    public SkillDefinition get(String skillId) {
        return skills.get(skillId);
    }

    @Override
    public String buildSystemPrompt(String skillId) {
        SkillDefinition skill = skills.get(skillId);
        if (skill == null) {
            throw new IllegalArgumentException("Unknown skill: " + skillId);
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append(skill.getSkillBody().trim());
        if (skill.getReferenceBody() != null && !skill.getReferenceBody().isBlank()) {
            prompt.append("\n\n---\n\n");
            prompt.append(skill.getReferenceBody().trim());
        }
        if (skill.getOverlayBody() != null && !skill.getOverlayBody().isBlank()) {
            prompt.append("\n\n---\n\n");
            prompt.append(skill.getOverlayBody().trim());
        }
        return prompt.toString();
    }

    private void loadSkill(String skillId) throws IOException {
        String skillPath = SKILLS_ROOT + skillId + "/" + SKILL_FILE;
        String referencePath = SKILLS_ROOT + skillId + "/" + REFERENCE_FILE;
        String overlayPath = SKILLS_ROOT + skillId + "/" + OVERLAY_FILE;

        String skillContent = readResource(skillPath);
        if (skillContent == null) {
            log.warn("SKILL.md not found for skill {}", skillId);
            return;
        }

        FrontMatter frontMatter = parseFrontMatter(skillContent);
        String referenceBody = readResource(referencePath);
        if (referenceBody == null) {
            referenceBody = "";
        }
        String overlayBody = readResource(overlayPath);
        if (overlayBody == null) {
            overlayBody = "";
        }

        String name = frontMatter.name() != null ? frontMatter.name() : skillId;
        String description = frontMatter.description() != null ? frontMatter.description() : "";

        skills.put(skillId, new SkillDefinition(
                skillId, name, description, frontMatter.body(), referenceBody, overlayBody));
    }

    private List<String> discoverSkillIds() throws IOException {
        List<String> skillIds = new ArrayList<>();
        Enumeration<URL> roots = classLoader.getResources(SKILLS_ROOT.substring(0, SKILLS_ROOT.length() - 1));
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if ("file".equals(root.getProtocol())) {
                collectSkillIdsFromFileUrl(root, skillIds);
            } else if ("jar".equals(root.getProtocol())) {
                collectSkillIdsFromJar(root, skillIds);
            }
        }
        return skillIds;
    }

    private void collectSkillIdsFromDirectory(Path skillsDir, List<String> skillIds) throws IOException {
        if (!Files.isDirectory(skillsDir)) {
            return;
        }
        try (var stream = Files.list(skillsDir)) {
            stream.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(SKILL_FILE)))
                    .map(dir -> dir.getFileName().toString())
                    .forEach(skillIds::add);
        }
    }

    private void collectSkillIdsFromFileUrl(URL fileUrl, List<String> skillIds) throws IOException {
        try {
            collectSkillIdsFromDirectory(Path.of(fileUrl.toURI()), skillIds);
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid skills directory URL: " + fileUrl, e);
        }
    }

    private void collectSkillIdsFromJar(URL jarRootUrl, List<String> skillIds) throws IOException {
        JarURLConnection connection = (JarURLConnection) jarRootUrl.openConnection();
        try (JarFile jarFile = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(SKILLS_ROOT) && name.endsWith("/" + SKILL_FILE) && !entry.isDirectory()) {
                    String relative = name.substring(SKILLS_ROOT.length());
                    int slash = relative.indexOf('/');
                    if (slash > 0) {
                        skillIds.add(relative.substring(0, slash));
                    }
                }
            }
        }
    }

    private String readResource(String path) throws IOException {
        URL url = classLoader.getResource(path);
        if (url == null) {
            return null;
        }
        try (InputStream input = url.openStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static FrontMatter parseFrontMatter(String content) {
        if (!content.startsWith("---")) {
            return new FrontMatter(null, null, content);
        }
        int end = content.indexOf("---", 3);
        if (end < 0) {
            return new FrontMatter(null, null, content);
        }

        String frontMatterBlock = content.substring(3, end).trim();
        String body = content.substring(end + 3).stripLeading();

        String name = null;
        String description = null;
        StringBuilder descriptionBuilder = new StringBuilder();
        boolean inDescription = false;

        for (String line : frontMatterBlock.split("\n")) {
            if (line.startsWith("name:")) {
                name = line.substring("name:".length()).trim();
                inDescription = false;
            } else if (line.startsWith("description:")) {
                descriptionBuilder.append(line.substring("description:".length()).trim());
                inDescription = true;
            } else if (inDescription && !line.isBlank()) {
                if (!descriptionBuilder.isEmpty()) {
                    descriptionBuilder.append(' ');
                }
                descriptionBuilder.append(line.trim());
            } else {
                inDescription = false;
            }
        }

        if (!descriptionBuilder.isEmpty()) {
            description = descriptionBuilder.toString();
        }

        return new FrontMatter(name, description, body);
    }

    record FrontMatter(String name, String description, String body) {
    }
}
