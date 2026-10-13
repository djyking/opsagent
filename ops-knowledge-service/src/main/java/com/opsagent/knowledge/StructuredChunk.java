package com.opsagent.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示保留章节、页码、块类型和策略版本的结构化知识切片。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record StructuredChunk(
        String content,
        int tokenCount,
        List<String> headingPath,
        Integer pageStart,
        Integer pageEnd,
        List<BlockType> blockTypes,
        boolean containsCode,
        boolean containsTable,
        String sourceFormat,
        String strategyVersion,
        int documentVersion) {

    public StructuredChunk {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        blockTypes = blockTypes == null ? List.of() : List.copyOf(blockTypes);
    }

    String embeddingText(String documentTitle) {
        String section = headingPath.isEmpty() ? documentTitle : String.join(" > ", headingPath);
        return "文档：" + documentTitle + "\n章节：" + section + "\n\n正文：\n" + content;
    }

    Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkStrategyVersion", strategyVersion);
        metadata.put("documentVersion", documentVersion);
        metadata.put("headingPath", headingPath);
        metadata.put("blockTypes", blockTypes.stream().map(Enum::name).toList());
        metadata.put("pageStart", pageStart);
        metadata.put("pageEnd", pageEnd);
        metadata.put("containsCode", containsCode);
        metadata.put("containsTable", containsTable);
        metadata.put("sourceFormat", sourceFormat);
        return metadata;
    }
}
