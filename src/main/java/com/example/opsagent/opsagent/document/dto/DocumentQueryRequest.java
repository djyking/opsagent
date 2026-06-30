package com.example.opsagent.opsagent.document.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DocumentQueryRequest {

    @Min(1)
    private Long pageNo = 1L;

    @Min(1)
    @Max(100)
    private Long pageSize = 10L;

    private Long knowledgeBaseId;

    @Size(max = 255)
    private String fileName;

    @Size(max = 32)
    private String fileType;

    @Pattern(regexp = "PENDING|PROCESSING|SUCCESS|FAILED", message = "must be one of PENDING, PROCESSING, SUCCESS, FAILED")
    private String parseStatus;
}
