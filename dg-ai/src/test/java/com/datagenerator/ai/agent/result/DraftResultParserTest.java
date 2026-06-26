package com.datagenerator.ai.agent.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.application.session.TurnContinueMode;
import org.junit.jupiter.api.Test;

class DraftResultParserTest {

    @Test
    void merge_closedJson_extractsDraftYaml() {
        String text = jsonBlock("ok", "writer:\n  type: csv", true);

        DraftResultParser.MergeResult result =
                DraftResultParser.merge(text, null, TurnContinueMode.NONE);

        assertThat(result.incomplete()).isFalse();
        assertThat(result.merged()).isPresent();
        assertThat(result.merged().orElseThrow().draftYaml()).contains("type: csv");
    }

    @Test
    void merge_appendBlock_appendsToExisting() {
        String existing = "writer:\n  type: csv";
        String text = jsonBlock("", "tables:\n  - name: t1", true);

        DraftResultParser.MergeResult result = DraftResultParser.merge(
                text, existing, TurnContinueMode.APPEND);

        assertThat(result.merged().orElseThrow().draftYaml()).contains("type: csv");
        assertThat(result.merged().orElseThrow().draftYaml()).contains("name: t1");
    }

    @Test
    void merge_incompleteJson_marksIncomplete() {
        String text = "```json\n{\"message\":\"\",\"draftYaml\":\"writer:\\n  type: csv";

        DraftResultParser.MergeResult result =
                DraftResultParser.merge(text, null, TurnContinueMode.NONE);

        assertThat(result.incomplete()).isTrue();
        assertThat(result.merged()).isPresent();
    }

    @Test
    void merge_noneMode_placeholderSegment_preservesExistingDraft() {
        String existing = "writer:\n  type: csv\ntables:\n  - name: t1";
        String text = jsonBlock("已复制", "---", true);

        DraftResultParser.MergeResult result =
                DraftResultParser.merge(text, existing, TurnContinueMode.NONE);

        assertThat(result.merged()).isPresent();
        assertThat(result.merged().orElseThrow().draftYaml()).contains("name: t1");
        assertThat(result.merged().orElseThrow().draftYaml()).doesNotContain("---");
    }

    @Test
    void merge_noneMode_emptySegment_preservesExistingDraft() {
        String existing = "writer:\n  type: csv\ntables:\n  - name: t1";
        String text = jsonBlock("已复制", "", true);

        DraftResultParser.MergeResult result =
                DraftResultParser.merge(text, existing, TurnContinueMode.NONE);

        assertThat(result.merged().orElseThrow().draftYaml()).contains("name: t1");
    }

    private static String jsonBlock(String message, String draftYaml, boolean draftComplete) {
        String escapedYaml = draftYaml.replace("\n", "\\n").replace("\"", "\\\"");
        return """
                ```json
                {
                  "message": "%s",
                  "draftYaml": "%s",
                  "draftComplete": %s
                }
                ```
                """
                .formatted(message, escapedYaml, draftComplete);
    }
}
