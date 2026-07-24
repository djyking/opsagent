package com.example.opsagent.document.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileType) {
        return "md".equalsIgnoreCase(fileType) || "markdown".equalsIgnoreCase(fileType);
    }

    @Override
    public String parse(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to parse markdown file", exception);
        }
    }
}
