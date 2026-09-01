package com.example.opsagent.ai.vo;

import lombok.Data;

/**
 * 返回 AI 答案引用的文档、Chunk、页码和相关性。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class AiReferenceVO {

    private Long chunkId;

    private Long documentId;

    private Integer chunkIndex;

    private Integer pageNumber;

    private Double relevanceScore;

    private String excerpt;
}
