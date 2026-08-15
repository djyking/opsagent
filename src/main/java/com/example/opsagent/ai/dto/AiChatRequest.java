package com.example.opsagent.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AiChatRequest {

    @NotBlank
    private String question;

    private Long documentId;

    @Min(1)
    @Max(10)
    private Integer topN;
}
