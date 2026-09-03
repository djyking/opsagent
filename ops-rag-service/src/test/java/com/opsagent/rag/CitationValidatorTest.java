package com.opsagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证模型伪造的 Citation 不会作为可信来源返回。
 *
 * @author heyu
 * @since 2026/9/2
 */
class CitationValidatorTest {
    @Test
    void shouldRemoveUnknownCitationAndKeepRetrievedCitation() {
        RetrievedChunk chunk = new RetrievedChunk(
                10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);

        String answer = new CitationValidator().validate(
                "真实 [chunk:10]，伪造 [chunk:999]。", List.of(chunk));

        assertThat(answer).contains("[chunk:10]").doesNotContain("[chunk:999]");
    }
}
