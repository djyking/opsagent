package com.example.opsagent.document.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 返回文档切片的顺序、内容和引用元数据。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class DocumentChunkVO {

    private Long id;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private Integer tokenCount;

    private Integer pageNumber;

    private String sectionTitle;

    private LocalDateTime createTime;
}
