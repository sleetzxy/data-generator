package com.datagenerator.ai.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class YamlArtifactExtractorTest {

    @Test
    void extract_findsMarkedBlock() {
        String text = "说明\n<!-- dg-artifact:yaml -->\nwriter:\n  type: csv\n<!-- /dg-artifact -->";
        assertThat(YamlArtifactExtractor.extract(text).orElseThrow()).contains("type: csv");
    }

    @Test
    void extract_noMarker_returnsEmpty() {
        assertThat(YamlArtifactExtractor.extract("no yaml")).isEmpty();
    }

    @Test
    void extract_yamlFence_returnsContent() {
        String text = "说明\n```yaml\nwriter:\n  type: csv\n```\n";
        assertThat(YamlArtifactExtractor.extract(text).orElseThrow()).contains("type: csv");
    }
}
