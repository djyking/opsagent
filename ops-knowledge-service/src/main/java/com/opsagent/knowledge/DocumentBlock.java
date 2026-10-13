package com.opsagent.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 保存解析阶段识别出的文档结构、章节路径、页码和原文顺序。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record DocumentBlock(
        BlockType type,
        String text,
        List<String> headingPath,
        Integer pageStart,
        Integer pageEnd,
        int order,
        Map<String, Object> metadata) {

    public DocumentBlock {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
