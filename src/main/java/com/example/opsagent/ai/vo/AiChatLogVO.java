package com.example.opsagent.ai.vo;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 返回 AI 问答处理记录和可选引用详情。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class AiChatLogVO {

    private Long id;
    private Long ticketId;
    private Long documentId;
    private Long userId;
    private String question;
    private String answer;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private String status;
    private String errorMessage;
    private Long costTimeMs;
    private List<AiReferenceVO> references;
    private LocalDateTime createTime;
}
