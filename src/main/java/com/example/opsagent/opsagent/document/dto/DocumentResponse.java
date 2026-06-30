package com.example.opsagent.opsagent.document.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentResponse {

    private Long id;

    private Long knowledgeBaseId;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private String parseStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
