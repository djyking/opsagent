package com.example.opsagent.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiChatRequest {

    @NotBlank
    private String question;

    private Long documentId;

    private Integer topN;
}
