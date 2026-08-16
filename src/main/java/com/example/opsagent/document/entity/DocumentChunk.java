package com.example.opsagent.document.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 映射可检索、可排序和可引用的文档切片。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private Integer tokenCount;

    private Integer pageNumber;

    private String sectionTitle;

    private String metadataJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
