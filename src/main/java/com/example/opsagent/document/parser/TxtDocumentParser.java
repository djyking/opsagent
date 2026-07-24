package com.example.opsagent.document.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class TxtDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileType) {
        return "txt".equalsIgnoreCase(fileType);
    }

    @Override
    public String parse(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to parse txt file", exception);
        }
    }
}
