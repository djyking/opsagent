package com.example.opsagent.opsagent.knowledgebase.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeBaseResponse {

    private Long id;

    private String name;

    private String description;

    private String owner;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
