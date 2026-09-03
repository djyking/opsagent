package com.example.opsagent.document.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射工单文档的存储、安全校验与解析状态元数据。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
@TableName("document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private String originalName;

    private String storageName;

    private String storagePath;

    private String contentType;

    private String fileExtension;

    private Long fileSize;

    private String fileHash;

    private String parseStatus;

    private String parseError;

    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic private Integer deleted;
}
