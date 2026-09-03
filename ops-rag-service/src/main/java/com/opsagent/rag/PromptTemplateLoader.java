package com.opsagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 从版本化 YAML 读取并校验 RAG 提示词，避免业务规则散落在 Java 字符串中。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Component
public class PromptTemplateLoader {
    private final PromptTemplate template;

    PromptTemplateLoader() {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            template = mapper.readValue(
                    new ClassPathResource("prompts/rag-answer.yml").getInputStream(),
                    PromptTemplate.class);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 RAG Prompt 模板", exception);
        }
        if (template.system() == null
                || template.system().isBlank()
                || template.user() == null
                || !template.user().contains("{{question}}")
                || !template.user().contains("{{context}}")) {
            throw new IllegalStateException("RAG Prompt 模板结构不完整");
        }
    }

    PromptTemplate get() {
        return template;
    }

    /**
     * 表示 YAML 中可审计的模板版本及系统、用户提示词。
     *
     * @author heyu
     * @since 2026/8/31
     */
    public record PromptTemplate(String version, String system, String user) {}
}
