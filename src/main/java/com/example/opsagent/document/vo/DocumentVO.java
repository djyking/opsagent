package com.example.opsagent.document.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 返回工单文档元数据，不暴露服务器绝对路径。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class DocumentVO {

    private Long id;

    private Long ticketId;

    private String originalName;

    private String contentType;

    private String fileExtension;

    private Long fileSize;

    private String fileHash;

    private String parseStatus;

    private String parseError;

    private Long createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
