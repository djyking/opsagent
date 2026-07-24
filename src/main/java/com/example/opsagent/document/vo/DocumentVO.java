package com.example.opsagent.document.vo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class DocumentVO {

    private Long id;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private String storagePath;

    private String status;

    private String uploader;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
