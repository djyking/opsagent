package com.opsagent.rag;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将真实检索片段及元数据放入明确边界，并控制传给模型的上下文大小。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Component
public class PromptBuilder {
    private final PromptTemplateLoader loader;
    private final RagProperties ragProperties;
    private final AiProperties aiProperties;

    PromptBuilder(
            PromptTemplateLoader loader,
            RagProperties ragProperties,
            AiProperties aiProperties) {
        this.loader = loader;
        this.ragProperties = ragProperties;
        this.aiProperties = aiProperties;
    }

    LlmRequest build(String question, List<RetrievedChunk> chunks) {
        String context = context(chunks);
        PromptTemplateLoader.PromptTemplate template = loader.get();
        String user = template.user()
                .replace("{{question}}", question)
                .replace("{{context}}", context);
        return new LlmRequest(template.system(), user, aiProperties.getMaxOutputTokens());
    }

    private String context(List<RetrievedChunk> chunks) {
        int budget = Math.max(1000, ragProperties.getContextCharacterBudget());
        StringBuilder result = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            String block = block(chunk);
            if (result.length() + block.length() > budget) {
                int remaining = budget - result.length();
                if (remaining > 200) {
                    result.append(block, 0, Math.min(remaining, block.length()));
                }
                break;
            }
            result.append(block);
        }
        return result.isEmpty() ? "（没有检索到知识片段）" : result.toString();
    }

    private String block(RetrievedChunk chunk) {
        return "--- BEGIN KNOWLEDGE CHUNK ---\n"
                + "citation: [chunk:" + chunk.chunkId() + "]\n"
                + "document_name: " + safe(chunk.documentName()) + "\n"
                + "document_id: " + chunk.documentId() + "\n"
                + "chunk_index: " + chunk.chunkIndex() + "\n"
                + "page: " + (chunk.page() == null ? "unknown" : chunk.page()) + "\n"
                + "version: " + (chunk.version() == null ? "unknown" : chunk.version()) + "\n"
                + "update_time: " + safe(chunk.updateTime()) + "\n"
                + "content:\n" + chunk.content() + "\n"
                + "--- END KNOWLEDGE CHUNK ---\n";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replace('\n', ' ');
    }
}
