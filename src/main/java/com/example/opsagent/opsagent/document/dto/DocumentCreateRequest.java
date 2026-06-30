package com.example.opsagent.opsagent.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DocumentCreateRequest {

    @NotNull
    private Long knowledgeBaseId;

    @NotBlank
    @Size(max = 255)
    private String fileName;

    @Size(max = 32)
    private String fileType;

    @NotNull
    @Min(0)
    private Long fileSize;

    @NotBlank
    @Pattern(regexp = "PENDING|PROCESSING|SUCCESS|FAILED", message = "must be one of PENDING, PROCESSING, SUCCESS, FAILED")
    private String parseStatus;
}
