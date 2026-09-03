package com.example.opsagent.document.parser;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 以 UTF-8 读取纯文本文档内容。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Component
public class TxtDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileType) {
        return "txt".equalsIgnoreCase(fileType);
    }

    @Override
    public String parse(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("TXT 文件读取失败", exception);
        }
    }
}
