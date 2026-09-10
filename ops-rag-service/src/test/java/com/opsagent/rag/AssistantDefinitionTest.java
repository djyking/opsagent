package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 明确公开定义问题只以技术主题召回，不以篇幅要求或问句噪音证明相关性。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AssistantDefinitionTest {
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "什么是布隆过滤器？请用两句话解释。|布隆过滤器",
                "What is a Bloom filter? Please explain briefly.|Bloom filter",
                "解释一下 Bloom filter 的原理|Bloom filter",
                "请问什么是连接池？|连接池"
            })
    void removesQuestionAndLengthInstructions(String question, String topic) {
        assertThat(AssistantIntent.definitionTopic(question)).isEqualTo(topic);
    }

    @Test
    void doesNotRewriteUnrelatedIntent() {
        assertThat(AssistantIntent.definitionTopic("当前服务为什么异常")).isNull();
        assertThat(AssistantIntent.definitionTopic("请继续上一个方案")).isNull();
        assertThat(AssistantIntent.definitionTopic("系统怎么用")).isNull();
    }
}
