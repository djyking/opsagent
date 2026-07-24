package com.example.opsagent.ai.vo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AiChatLogVO {

    private Long id;

    private String question;

    private String answer;

    private Long documentId;

    private String usedChunks;

    private Long costTimeMs;

    private LocalDateTime createTime;
}
