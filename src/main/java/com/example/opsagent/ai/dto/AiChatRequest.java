package com.example.opsagent.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 承载工单范围的文档问题与可选文档限定。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class AiChatRequest {

    @NotBlank
    @Size(max = 2000)
    private String question;

    private Long documentId;

    @Min(1)
    @Max(10)
    private Integer topK;
}
