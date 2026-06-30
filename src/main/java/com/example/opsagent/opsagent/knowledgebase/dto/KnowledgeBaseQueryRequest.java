package com.example.opsagent.opsagent.knowledgebase.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeBaseQueryRequest {

    @Min(1)
    private Long pageNo = 1L;

    @Min(1)
    @Max(100)
    private Long pageSize = 10L;

    @Size(max = 100)
    private String name;

    @Size(max = 64)
    private String owner;
}
