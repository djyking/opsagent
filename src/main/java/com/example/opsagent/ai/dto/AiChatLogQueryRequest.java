package com.example.opsagent.ai.dto;

import lombok.Data;

@Data
public class AiChatLogQueryRequest {

    private Long documentId;

    private Long pageNum = 1L;

    private Long pageSize = 10L;
}
