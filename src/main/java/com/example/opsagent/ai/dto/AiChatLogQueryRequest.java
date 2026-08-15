package com.example.opsagent.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AiChatLogQueryRequest {

    private Long documentId;

    @Min(1)
    private Long pageNum = 1L;

    @Min(1)
    @Max(100)
    private Long pageSize = 10L;
}
