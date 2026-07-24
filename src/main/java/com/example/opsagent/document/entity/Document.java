package com.example.opsagent.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private String storagePath;

    private String status;

    private String uploader;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
