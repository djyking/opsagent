package com.example.opsagent.document.parser;

import org.springframework.web.multipart.MultipartFile;

public interface DocumentParser {

    boolean supports(String fileType);

    String parse(MultipartFile file);
}
