package com.example.opsagent.document.dto;

import lombok.Data;

@Data
public class DocumentQueryRequest {

    private String fileName;

    private String status;

    private String uploader;

    private Long pageNum = 1L;

    private Long pageSize = 10L;
}
