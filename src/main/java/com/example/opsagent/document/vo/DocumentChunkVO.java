package com.example.opsagent.document.vo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class DocumentChunkVO {

    private Long id;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private Integer tokenEstimate;

    private LocalDateTime createTime;
}
