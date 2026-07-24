package com.example.opsagent.ai.vo;

import lombok.Data;

@Data
public class AiChatVO {

    private String answer;

    private Long documentId;

    private String usedChunks;

    private Long costTimeMs;
}
