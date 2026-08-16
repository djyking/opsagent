package com.example.opsagent.ai.vo;

import java.util.List;

import lombok.Data;

/**
 * 返回 AI 回答、处理记录与结构化引用。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class AiChatVO {

    private Long questionId;

    private Long ticketId;

    private Long documentId;

    private String answer;

    private String modelName;

    private Long costTimeMs;

    private List<AiReferenceVO> references;
}
