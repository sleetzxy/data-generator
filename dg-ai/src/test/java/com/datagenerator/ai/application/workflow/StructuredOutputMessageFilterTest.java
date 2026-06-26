package com.datagenerator.ai.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.agent.result.DraftResultParser;
import org.junit.jupiter.api.Test;

class StructuredOutputMessageFilterTest {

    @Test
    void accept_structuredJson_emitsOnlyMessageField() {
        StructuredOutputMessageFilter filter = new StructuredOutputMessageFilter();

        assertThat(filter.accept("```json\n{\"message\":\"你好，")).isEqualTo("你好，");
        assertThat(filter.accept("已生成 writer。")).isEqualTo("已生成 writer。");
        assertThat(filter.accept("\",\"draftYaml\":\"writer:\\n  type: csv")).isEmpty();
        assertThat(filter.accept("\",\"draftComplete\":true}\n```")).isEmpty();
    }

    @Test
    void accept_plainTextWithoutJson_passthroughAfterThreshold() {
        StructuredOutputMessageFilter filter = new StructuredOutputMessageFilter();
        String prefix = "这是一段没有 JSON 围栏的纯文本回复内容，长度足够触发直通模式。";

        assertThat(filter.accept(prefix)).isEqualTo(prefix);
        assertThat(filter.accept(" 继续。")).isEqualTo(" 继续。");
    }

    @Test
    void extractPartialMessage_readsEscapedCharacters() {
        String text =
                """
                ```json
                {"message":"line1\\nline2","draftYaml":"x","draftComplete":true}
                ```
                """;

        assertThat(DraftResultParser.extractPartialMessage(text)).isEqualTo("line1\nline2");
    }

    @Test
    void flush_emitsBufferedPlainTextWhenNoJson() {
        StructuredOutputMessageFilter filter = new StructuredOutputMessageFilter();

        assertThat(filter.accept("短文本")).isEmpty();
        assertThat(filter.flush()).isEqualTo("短文本");
    }

    @Test
    void reconcileFullText_emitsRemainingMessage() {
        StructuredOutputMessageFilter filter = new StructuredOutputMessageFilter();
        filter.accept("```json\n{\"message\":\"你好");
        String trailing = filter.reconcileFullText("```json\n{\"message\":\"你好世界\",\"draftYaml\":\"x\"}");
        assertThat(trailing).isEqualTo("世界");
    }
}
